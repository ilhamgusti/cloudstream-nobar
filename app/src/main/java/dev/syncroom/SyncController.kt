// Vendored from syncroom@6b4705e (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here.
package dev.syncroom

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

// Keeps one local player on the room's anchor line.
//
//   line(t) = position + (t − anchorAt)/1000 × rate   while advancing
//             position                                 while paused/waiting
//
// Latency doesn't move the target: events carry server timestamps and the line
// is evaluated on the estimated SERVER clock. Followers converge with a ±5 %
// rate nudge (small drift) or a hard seek with cooldown (large drift). Stalls
// are reported; when the room waits (host stalled, or waitForAll) every player
// holds and resumes together. Port of src/client/sync.ts — keep them in step.

enum class MediaEvent { PLAY, PAUSE, SEEKED, ENDED, WAITING, PLAYING }

/** Wrap any player (ExoPlayer, MediaPlayer, VLC…). Call from the scheduler thread. */
interface MediaAdapter {
    val rateControl: Boolean get() = true
    val seekable: Boolean get() = true

    /**
     * Load [media]. Evaluate [startAt] when the media can seek (after the load
     * latency), then call [done] — on the scheduler thread.
     */
    fun load(media: MediaRef, startAt: () -> Double, autoplay: Boolean, done: () -> Unit)
    /** Seconds, or null while nothing seekable is loaded. */
    fun getTime(): Double?
    fun isPaused(): Boolean
    fun isBuffering(): Boolean
    /** false = blocked (needs a user gesture). */
    fun play(): Boolean
    fun pause()
    fun seek(t: Double)
    fun setRate(r: Double)
    /** Report user/player events: play, pause, seeked, ended, waiting (stall), playing (recovered). */
    fun setListener(l: ((MediaEvent) -> Unit)?)
}

data class SyncConfig(
    val tickMs: Long = 1000,
    /** Below this drift: leave it alone. */
    val deadBandSec: Double = 0.08,
    /** Above this: hard seek (players without rate control use seekOnlySec). */
    val hardSec: Double = 1.0,
    val seekOnlySec: Double = 0.6,
    /** Applying a remote play/seek: seek if further than this. */
    val remoteSeekTolSec: Double = 1.0,
    val rateGain: Double = 0.5,
    val rateMax: Double = 0.05,
    val seekCooldownMs: Double = 3000.0,
    /** Our own commands' media events are ignored for this long. */
    val echoWindowMs: Double = 700.0,
    /** Debounce before reporting a stall (short hiccups are normal). */
    val stallReportMs: Long = 300,
    /** Host re-anchors the room when its player strays this far from the line. */
    val hostReanchorSec: Double = 0.25,
    val hostCheckMs: Double = 2000.0,
    /** A stall starting this soon after our own seek is blamed on the seek. */
    val seekStallAttributionMs: Double = 500.0,
    /** Upper bound for the learned seek cost (lead added to catch-up seeks). */
    val maxSeekCostSec: Double = 5.0,
    /** Stalls (not caused by our seeks) within the window that mark a struggling player. */
    val struggleStalls: Int = 3,
    val struggleWindowMs: Double = 60_000.0,
    /** Followers may pause locally without affecting the room. */
    val allowLocalPause: Boolean = true,
)

enum class SyncStatus { IDLE, SYNCED, DRIFTING, SEEKING, WAITING, LOCAL_PAUSE, NEEDS_GESTURE, STRUGGLING }

class SyncController(
    private val client: RoomLink,
    private val media: MediaAdapter,
    private val scheduler: Scheduler,
    private val cfg: SyncConfig = SyncConfig(),
    /** drift: + = we're ahead (s). waitingFor: members the room waits for. */
    private val onStatus: ((SyncStatus, drift: Double?, waitingFor: List<String>) -> Unit)? = null,
    /** This player keeps stalling — a hint to drop to a lower quality. */
    private val onStruggling: (() -> Unit)? = null,
    /** Extra fields for the periodic stats report, e.g. { it.put("quality", "480p") }. */
    private val statsExtra: ((JSONObject) -> Unit)? = null,
) {
    var status = SyncStatus.IDLE
        private set

    private var itemId: String? = null
    private var epoch = -1
    private var echoUntil = 0.0
    private var lastSeekAt = Double.NEGATIVE_INFINITY
    private var userPaused = false
    private var stallTimer: Cancellable? = null
    private var stallReported = false
    private var lastHostCheck = 0.0
    private var tickTimer: Cancellable? = null
    private var disposed = false
    private val unsubscribe: () -> Unit
    private var loading = false

    /**
     * Learned cost of a catch-up seek (seconds of buffering it triggers).
     * Seeking to `line + cost` lands in sync AFTER the rebuffer; otherwise a
     * slow-seeking player seeks, rebuffers, is behind again, seeks again…
     */
    var seekCostSec = 0.0
        private set
    private var lastOwnSeekAt = Double.NEGATIVE_INFINITY
    private var seekStallStartedAt: Double? = null
    private var seekEvalPending = false
    private val stalls = ArrayList<Double>()
    private var struggling = false
    /** Epoch we already sent `ready` for (start barrier). */
    private var readyEpoch = -1
    private var startTimer: Cancellable? = null
    var lastDrift: Double? = null
        private set
    private var lastStatsAt = 0.0

    init {
        unsubscribe = client.addListener(object : RoomListener {
            override fun onSnapshot(view: RoomView) = applyRoom(Why.SNAPSHOT)
            override fun onEvent(event: RoomEvent) = onRoomEvent(event)
        })
        media.setListener { ev ->
            when (ev) {
                MediaEvent.PLAY, MediaEvent.PAUSE, MediaEvent.SEEKED, MediaEvent.ENDED -> onMedia(ev)
                MediaEvent.WAITING -> onWaiting(true)
                MediaEvent.PLAYING -> onWaiting(false)
            }
        }
        if (client.view != null) applyRoom(Why.SNAPSHOT)
        scheduleTick()
    }

    fun dispose() {
        disposed = true
        tickTimer?.cancel()
        stallTimer?.cancel()
        startTimer?.cancel()
        unsubscribe()
        media.setListener(null)
        media.setRate(1.0)
    }

    private fun scheduleTick() {
        tickTimer = scheduler.schedule(cfg.tickMs) {
            if (disposed) return@schedule
            tick()
            scheduleTick()
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private enum class Why { SNAPSHOT, EVENT, ROSTER }

    private val pb get() = client.view?.playback
    private val isHostSelf get() = client.view?.roster?.hostId == client.clientId
    private val canControl get() = client.view?.let { isHostSelf || it.settings.control == "everyone" || it.isCohost(client.clientId) } ?: false
    /** Host is the time reference (never corrected) unless the server says otherwise. */
    private val hostIsReference get() = isHostSelf && client.view?.sync?.reference != "server"
    private fun local() = client.clock.local()

    private fun line(): Double? {
        val v = client.view ?: return null
        val cur = v.current ?: return null
        return v.playback.positionAt(client.clock.now(), cur.durationSec)
    }

    private fun setStatus(s0: SyncStatus, drift: Double? = null) {
        val s = if (struggling && (s0 == SyncStatus.SYNCED || s0 == SyncStatus.DRIFTING)) SyncStatus.STRUGGLING else s0
        status = s
        if (drift != null) lastDrift = drift
        onStatus?.invoke(s, drift, pb?.waiting ?: emptyList())
    }

    private inline fun command(fn: () -> Unit) {
        echoUntil = local() + cfg.echoWindowMs
        fn()
    }

    private fun seek(t: Double) {
        lastSeekAt = local()
        lastOwnSeekAt = lastSeekAt
        seekEvalPending = true
        command { media.seek(maxOf(0.0, t)) }
    }

    /** Seek onto a MOVING line: aim ahead by what the seek itself will cost. */
    private fun catchUp(target: Double, rate: Double) = seek(target + seekCostSec * rate)

    private fun play() {
        echoUntil = local() + cfg.echoWindowMs
        if (!media.play()) setStatus(SyncStatus.NEEDS_GESTURE)
    }

    // --- inbound ---------------------------------------------------------------------------

    private fun onRoomEvent(e: RoomEvent) {
        when (e) {
            is RoomEvent.PlaybackChanged -> {
                // Our own play/pause/seek already happened on our player — don't fight it.
                if (e.origin == client.clientId && e.cause != "stall" && e.cause != "resume") {
                    epoch = e.playback.epoch
                    return
                }
                applyRoom(Why.EVENT)
            }
            is RoomEvent.NowPlaying -> applyRoom(Why.EVENT)
            is RoomEvent.RosterChanged -> applyRoom(Why.ROSTER)
            else -> {}
        }
    }

    /** Bring the player in line with the mirror (load, play/pause, position). */
    private fun applyRoom(why: Why) {
        if (disposed) return
        if (loading) return // re-evaluated with the latest mirror once the load lands
        val v = client.view ?: return
        val cur = v.current
        val p = v.playback
        if (cur == null) {
            itemId = null
            command { media.pause() }
            setStatus(SyncStatus.IDLE)
            return
        }
        if (cur.id != itemId || p.epoch != epoch) {
            val isNewItem = cur.id != itemId
            itemId = cur.id
            epoch = p.epoch
            userPaused = false
            stallReported = false
            if (isNewItem) {
                echoUntil = local() + 5000
                loading = true
                media.load(cur, { line() ?: 0.0 }, p.advancing) {
                    scheduler.post {
                        loading = false
                        applyRoom(Why.EVENT)
                    }
                }
                return
            }
        }
        if (hostIsReference && why == Why.ROSTER) return
        val target = line() ?: return
        val pos = media.getTime()
        val me = client.clientId ?: ""
        if (!p.advancing && p.playing && me in p.waiting && !stallReported && stallTimer == null) {
            // Start barrier: the room waits until our player has loaded. Once it can
            // play, hold at the start and say so (still prebuffering → PLAYING re-runs this).
            if (media.isBuffering()) return setStatus(SyncStatus.WAITING)
            if (!media.isPaused()) command { media.pause() }
            if (pos != null && media.seekable && abs(pos - target) > 0.25) seek(target)
            if (readyEpoch != p.epoch) {
                readyEpoch = p.epoch
                client.send(Intents.ready(p.epoch, client.clock.rtt))
            }
            setStatus(SyncStatus.WAITING)
            return
        }
        if (!p.advancing && p.playing && me in p.waiting) {
            // The room is waiting for US: keep buffering (pausing would stop the
            // player from ever reporting it recovered).
            setStatus(SyncStatus.WAITING)
            return
        }
        if (!p.advancing) {
            // Paused, or the room waits for someone else: hold at the frozen position.
            if (!media.isPaused()) command { media.pause() }
            if (pos != null && media.seekable && abs(pos - target) > 0.25) seek(target)
            setStatus(if (p.waiting.isNotEmpty()) SyncStatus.WAITING else SyncStatus.SYNCED)
            return
        }
        if (userPaused) return
        val startIn = p.anchorAt - client.clock.now()
        if (startIn > 30) {
            // Synchronized start: hold at the start position, press play at the agreed server instant.
            if (!media.isPaused()) command { media.pause() }
            if (pos != null && media.seekable && abs(pos - p.position) > 0.25) seek(p.position)
            startTimer?.cancel()
            startTimer = scheduler.schedule(startIn.roundToLong()) {
                startTimer = null
                applyRoom(Why.EVENT)
            }
            setStatus(SyncStatus.WAITING)
            return
        }
        if (pos != null && media.seekable && abs(pos - target) > cfg.remoteSeekTolSec) catchUp(target, p.rate)
        if (media.isPaused()) play()
        media.setRate(p.rate)
    }

    // --- drift loop ------------------------------------------------------------------------

    private fun tick() {
        maybeReportStats()
        val p = pb ?: return
        val target = line() ?: return
        if (client.status != Status.JOINED || loading) return
        val pos = media.getTime() ?: return
        val now = local()
        if (struggling && now - (stalls.lastOrNull() ?: 0.0) > cfg.struggleWindowMs) struggling = false
        if (seekEvalPending && now - lastOwnSeekAt > cfg.seekStallAttributionMs) {
            seekEvalPending = false // that seek was free: decay the learned cost
            seekCostSec *= 0.5
        }

        if (hostIsReference) {
            // The host is the reference: re-anchor the room when we stray from the
            // line (e.g. our player was slow to start). Never self-correct.
            if (p.advancing && !media.isPaused() && !media.isBuffering() && now - lastHostCheck >= cfg.hostCheckMs) {
                lastHostCheck = now
                if (abs(pos - target) > cfg.hostReanchorSec) client.send(Intents.sync(p.epoch, pos, client.clock.now()))
            }
            setStatus(if (p.waiting.isNotEmpty()) SyncStatus.WAITING else SyncStatus.SYNCED, 0.0)
            return
        }

        if (userPaused) return setStatus(SyncStatus.LOCAL_PAUSE)
        if (!p.advancing) return setStatus(if (p.waiting.isNotEmpty()) SyncStatus.WAITING else SyncStatus.SYNCED)
        if (media.isPaused() || media.isBuffering() || now < echoUntil) {
            media.setRate(p.rate)
            return
        }
        val drift = pos - target // + = ahead of the room
        val ad = abs(drift)
        val hard = if (media.rateControl) cfg.hardSec else cfg.seekOnlySec
        if (ad > hard && now - lastSeekAt > cfg.seekCooldownMs && media.seekable) {
            catchUp(target, p.rate)
            media.setRate(p.rate)
            setStatus(SyncStatus.SEEKING, drift)
        } else if (media.rateControl && ad > cfg.deadBandSec) {
            val nudge = (cfg.rateGain * drift).coerceIn(-cfg.rateMax, cfg.rateMax)
            media.setRate(p.rate * (1 - nudge))
            setStatus(SyncStatus.DRIFTING, drift)
        } else {
            media.setRate(p.rate)
            setStatus(SyncStatus.SYNCED, drift)
        }
    }

    // --- outbound --------------------------------------------------------------------------

    /** Periodic telemetry so the room sees everyone's drift/status. */
    private fun maybeReportStats() {
        val every = client.view?.sync?.statsIntervalMs ?: 0
        val now = local()
        if (every <= 0 || client.status != Status.JOINED || now - lastStatsAt < every) return
        lastStatsAt = now
        val st = JSONObject()
            .put("status", status.name.lowercase().replace('_', '-'))
            .put("rttMs", client.clock.rtt.roundToLong())
            .put("seekCostSec", Math.round(seekCostSec * 100) / 100.0)
        lastDrift?.let { st.put("drift", Math.round(it * 1000) / 1000.0) }
        statsExtra?.invoke(st)
        client.notify(Intents.stats(st))
    }

    private fun onMedia(kind: MediaEvent) {
        val p = pb ?: return
        if (itemId == null || loading) return
        if (kind == MediaEvent.ENDED) {
            client.send(Intents.ended(p.epoch))
            return
        }
        if (local() < echoUntil) return // caused by us
        val pos = media.getTime() ?: return
        val at = client.clock.now()

        if (canControl) {
            when (kind) {
                MediaEvent.PLAY -> client.send(Intents.play(p.epoch, pos, at))
                MediaEvent.PAUSE -> client.send(Intents.pause(p.epoch, pos, at))
                MediaEvent.SEEKED -> client.send(Intents.seek(p.epoch, pos, at))
                else -> {}
            }
            return
        }
        // Follower without control: local pause is allowed; resuming rejoins the line.
        when {
            kind == MediaEvent.PAUSE && cfg.allowLocalPause -> {
                userPaused = true
                setStatus(SyncStatus.LOCAL_PAUSE)
            }
            kind == MediaEvent.PLAY -> {
                userPaused = false
                applyRoom(Why.EVENT)
            }
            kind == MediaEvent.SEEKED -> applyRoom(Why.EVENT) // snap back to the room
            else -> {}
        }
    }

    /** Learn seek cost + detect a chronically stalling player. */
    private fun trackStall(waiting: Boolean) {
        val now = local()
        if (waiting) {
            if (now - lastOwnSeekAt <= cfg.seekStallAttributionMs) {
                seekEvalPending = false
                seekStallStartedAt = lastOwnSeekAt
                return
            }
            stalls.removeAll { now - it >= cfg.struggleWindowMs }
            stalls += now
            if (!struggling && stalls.size >= cfg.struggleStalls) {
                struggling = true
                setStatus(SyncStatus.STRUGGLING)
                onStruggling?.invoke()
            }
            return
        }
        seekStallStartedAt?.let { started ->
            val cost = (now - started) / 1000
            seekCostSec = minOf(cfg.maxSeekCostSec, if (seekCostSec > 0) 0.5 * seekCostSec + 0.5 * cost else cost)
            seekStallStartedAt = null
        }
    }

    private fun onWaiting(waiting: Boolean) {
        val p = pb ?: return
        trackStall(waiting)
        if (waiting) {
            if (stallTimer != null || stallReported) return
            stallTimer = scheduler.schedule(cfg.stallReportMs) {
                stallTimer = null
                if (disposed || !media.isBuffering()) return@schedule
                stallReported = true
                client.send(Intents.buffering(p.epoch, true, media.getTime()))
            }
        } else {
            stallTimer?.cancel()
            stallTimer = null
            if (stallReported) {
                stallReported = false
                client.send(Intents.buffering(pb?.epoch ?: p.epoch, false, at = client.clock.now()))
            } else if ((client.clientId ?: "") in p.waiting && readyEpoch != p.epoch) {
                applyRoom(Why.EVENT) // finished prebuffering during the start barrier
            }
        }
    }
}
