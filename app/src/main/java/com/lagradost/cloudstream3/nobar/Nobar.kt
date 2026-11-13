@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lagradost.cloudstream3.nobar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.MainThread
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import dev.syncroom.CallbackScheduler
import dev.syncroom.ControlRequest
import dev.syncroom.Intents
import dev.syncroom.MediaRef
import dev.syncroom.Member
import dev.syncroom.RoomClient
import dev.syncroom.RoomEvent
import dev.syncroom.RoomListener
import dev.syncroom.RoomView
import dev.syncroom.Status
import dev.syncroom.SyncController
import dev.syncroom.SyncStatus
import org.json.JSONObject

/**
 * Nobar (watch together) over a syncroom server.
 *
 * The room session belongs to the app, not to a player screen: the host can close the
 * player and start another title and the room follows, and a member whose player is
 * closed gets it reopened when the room moves on. Only [leave] ends it.
 *
 * A player screen plugs in through [Screen] while it's on screen ([attach]/[detach]);
 * the [SyncController] lives exactly as long as that attachment. Main thread only.
 */
@MainThread
object Nobar {
    /** The player screen, as the room sees it. */
    interface Screen {
        val adapter: NobarAdapter

        /** The room item for what's on screen, with the position in seconds (null = nothing shareable). */
        fun nowShowing(): Pair<MediaRef, Double>?
    }

    private const val PLUGIN_WAIT_TRIES = 20
    private val main = Handler(Looper.getMainLooper())
    private val scheduler = CallbackScheduler(
        post = { main.post(it) },
        postDelayed = { r, ms -> main.postDelayed(r, ms) },
        cancel = { main.removeCallbacks(it) },
    )

    var session: Session? = null
        private set

    private val listeners = LinkedHashSet<() -> Unit>()

    /** Called whenever anything about the session changes (status, roster, sync state). */
    fun addListener(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** Join [code] (or create it when [create]); leaves the current room first. */
    fun start(context: Context, code: String, create: Boolean): Session? {
        val prefs = NobarPrefs(context)
        if (prefs.server.isEmpty()) {
            toast(context.getString(R.string.nobar_no_server))
            return null
        }
        leave()
        prefs.lastRoom = code
        return Session(context.applicationContext, prefs, code, create).also {
            session = it
            changed()
        }
    }

    /** Leave, handing the host seat to [to] first (leave anyway if the ack is slow). */
    fun leaveHandingOver(to: String) {
        val s = session ?: return
        var done = false
        val go = Runnable {
            if (!done) {
                done = true
                if (session === s) leave()
            }
        }
        s.client.send(Intents.hostTransfer(to)) { main.post(go) }
        main.postDelayed(go, 2000)
    }

    fun leave() {
        val s = session ?: return
        session = null
        s.close()
        changed()
    }

    fun attach(screen: Screen) = session?.attach(screen)
    fun detach(screen: Screen) = session?.detach(screen)

    /** The screen switched to another item on its own (next episode, episode list, new title). */
    fun onLocalMedia(screen: Screen) = session?.onLocalMedia(screen)

    /** In a room where we don't control playback, episode switching is the host's call. */
    fun blocksLocalSwitch(): Boolean {
        val s = session ?: return false
        if (s.status != Status.JOINED || s.canControl) return false
        toast(s.ctx.getString(R.string.nobar_host_only))
        return true
    }

    /** "pause", "jump to 12:34", "play Dune · S1:E2"… for a request. */
    fun actionLabel(ctx: Context, r: ControlRequest): String = when (r.action) {
        "pause" -> ctx.getString(R.string.nobar_act_pause)
        "play" -> ctx.getString(R.string.nobar_act_play)
        "seek" -> ctx.getString(R.string.nobar_act_seek, formatTime(r.position ?: 0.0))
        "next" -> ctx.getString(R.string.nobar_act_next)
        "prev" -> ctx.getString(R.string.nobar_act_prev)
        "load" -> ctx.getString(R.string.nobar_act_load, r.media?.title ?: "?")
        "host" -> ctx.getString(R.string.nobar_act_host)
        else -> r.action
    }

    private fun formatTime(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun toast(text: String) {
        CommonActivity.showToast(CommonActivity.activity, text, Toast.LENGTH_SHORT)
    }

    class Session internal constructor(
        internal val ctx: Context,
        prefs: NobarPrefs,
        val code: String,
        /** We made this room: what we're watching is what the room plays. */
        private val created: Boolean,
    ) {
        val client = RoomClient(
            prefs.wsUrl, code, prefs.name,
            token = { prefs.token() },
            scheduler = scheduler,
            create = created,
        )
        private var screen: Screen? = null
        private var sync: SyncController? = null
        private var closed = false
        /** A room item we navigated a player to; its screen follows the room, it doesn't offer. */
        private var autoOpening: String? = null
        /** The first screen of a join-only session follows the room (it doesn't take it over). */
        private var firstAttach = true

        var status = Status.CONNECTING
            private set
        var syncStatus = SyncStatus.IDLE
            private set
        var drift: Double? = null
            private set
        var waitingFor: List<String> = emptyList()
            private set
        var error: String? = null
            private set

        val view: RoomView? get() = client.view
        val me get() = client.clientId
        val members: List<Member> get() = view?.roster?.members ?: emptyList()
        val hostId get() = view?.roster?.hostId
        val isHost get() = me != null && me == hostId
        val everyone get() = view?.settings?.control == "everyone"
        val canControl get() = isHost || everyone || view?.isCohost(me) == true
        val current: MediaRef? get() = view?.current
        val cohosts: List<String> get() = view?.roster?.cohosts ?: emptyList()
        /** Pending asks from members without control; the host and co-hosts answer them. */
        val requests: List<ControlRequest> get() = view?.requests ?: emptyList()
        private val seenRequests = HashSet<String>()
        /** Last host we saw, to announce a handover; null until the first snapshot. */
        private var knownHost: String? = null
        val playerOpen get() = screen != null

        fun nameOf(id: String) = members.firstOrNull { it.clientId == id }?.name ?: id

        init {
            client.addListener(object : RoomListener {
                override fun onStatus(status: Status) {
                    this@Session.status = status
                    if (status == Status.JOINED) {
                        error = null
                        onJoined()
                    }
                    changed()
                }

                override fun onSnapshot(view: RoomView) = changed()

                override fun onEvent(event: RoomEvent) {
                    if (event is RoomEvent.NowPlaying) onNowPlaying(event.current)
                    if (event is RoomEvent.RequestsChanged) onRequests(event.requests)
                    if (event is RoomEvent.RosterChanged) onHostMaybeChanged()
                    changed()
                }

                override fun onJoinError(reason: String) {
                    error = when (reason) {
                        "pin-required", "wrong-pin" -> ctx.getString(R.string.nobar_err_locked)
                        "limited" -> ctx.getString(R.string.nobar_err_limited)
                        "blocked" -> ctx.getString(R.string.nobar_err_blocked)
                        "invalid" -> ctx.getString(R.string.nobar_err_invalid)
                        "not-found" -> ctx.getString(R.string.nobar_err_not_found, code)
                        else -> ctx.getString(R.string.nobar_err_generic, reason)
                    }
                    toast(error!!)
                    if (this@Session === session) leave()
                }

                override fun onKicked(blocked: Boolean) {
                    error = ctx.getString(if (blocked) R.string.nobar_err_blocked else R.string.nobar_kicked)
                    toast(error!!)
                    if (this@Session === session) leave()
                }
            })
        }

        private fun onJoined() {
            knownHost = hostId
            val s = screen
            if (s != null) startSync(s)
            else current?.let { openPlayer(it) }
                ?: toast(ctx.getString(R.string.nobar_joined_waiting, code))
        }

        private fun onNowPlaying(media: MediaRef?) {
            if (media == null || closed) return
            if (screen == null) openPlayer(media)
        }

        internal fun attach(s: Screen) {
            if (screen === s) return
            screen?.let { detach(it) }
            screen = s
            if (status == Status.JOINED) startSync(s)
            changed()
        }

        internal fun detach(s: Screen) {
            if (screen !== s) return
            sync?.dispose()
            sync = null
            s.adapter.release()
            screen = null
            syncStatus = SyncStatus.IDLE
            drift = null
            waitingFor = emptyList()
            changed()
        }

        /** Start following the room, after offering what's on screen if that's ours to decide. */
        private fun startSync(s: Screen) {
            if (sync != null || closed) return
            val mine = s.nowShowing()
            val follow = mine == null || mine.first.id == autoOpening || (firstAttach && !created && current != null)
            firstAttach = false
            autoOpening = null
            if (!follow && mine != null && canControl && mine.first.id != current?.id) {
                client.send(Intents.load(mine.first, mine.second, client.clock.now())) { createSync(s) }
            } else {
                createSync(s)
            }
        }

        private fun createSync(s: Screen) {
            if (sync != null || closed || screen !== s) return
            sync = SyncController(
                client, s.adapter, scheduler,
                onStatus = { st, d, w ->
                    val notify = st != syncStatus || w != waitingFor
                    syncStatus = st
                    drift = d
                    waitingFor = w
                    if (notify) changed()
                },
            )
        }

        internal fun onLocalMedia(s: Screen) {
            // sync == null: still offering this screen's item (startSync), nothing to add.
            if (screen !== s || status != Status.JOINED || closed || sync == null) return
            val (media, _) = s.nowShowing() ?: return
            if (media.id == current?.id || media.id == s.adapter.opening) return
            if (canControl) client.send(Intents.load(media, 0.0, client.clock.now()))
        }

        /** Open a player on the room's item (nobody's watching here yet). */
        private fun openPlayer(media: MediaRef, attempt: Int = 0) {
            if (closed || screen != null || autoOpening == media.id || current?.id != media.id) return
            val ep = NobarMedia.episodeOf(media) ?: run {
                toast(ctx.getString(R.string.nobar_unsupported))
                return
            }
            if (getApiFromNameNull(ep.apiName) == null) {
                // Opened from an invite on a cold start: extensions may still be loading.
                if (attempt < PLUGIN_WAIT_TRIES) {
                    main.postDelayed({ openPlayer(media, attempt + 1) }, 1000)
                } else {
                    toast(ctx.getString(R.string.nobar_missing_ext, ep.apiName))
                }
                return
            }
            val activity = CommonActivity.activity ?: return
            autoOpening = media.id
            activity.navigate(
                R.id.global_to_navigation_player,
                GeneratorPlayer.newInstance(RepoLinkGenerator(listOf(ep)), 0)
            )
        }

        private fun onHostMaybeChanged() {
            val h = hostId ?: return
            val before = knownHost
            knownHost = h
            if (before == null || before == h) return
            toast(
                if (h == me) ctx.getString(R.string.nobar_you_are_host)
                else ctx.getString(R.string.nobar_new_host, nameOf(h))
            )
        }

        /** Requests this member may answer: a host request only the host can approve. */
        val answerable: List<ControlRequest>
            get() = if (!canControl) emptyList() else requests.filter { it.action != "host" || isHost }

        /** Tell whoever can answer about a new ask (they open the sheet to approve it). */
        private fun onRequests(list: List<ControlRequest>) {
            val fresh = list.filter { seenRequests.add(it.id) }
            if (!canControl) return
            fresh.lastOrNull { it.from != me && (it.action != "host" || isHost) }?.let {
                toast(ctx.getString(R.string.nobar_request_toast, it.name, actionLabel(ctx, it)))
            }
        }

        /** Ask the host / a co-host for [action] (see [dev.syncroom.RequestActions]). */
        fun request(action: JSONObject) = client.request(action) { ack ->
            toast(
                if (ack.ok) ctx.getString(R.string.nobar_request_sent)
                else ctx.getString(R.string.nobar_request_failed, ack.reason ?: "?")
            )
        }

        fun resolve(id: String, approve: Boolean) = client.send(Intents.resolveRequest(id, approve))
        fun setCohost(clientId: String, on: Boolean) =
            client.send(Intents.role(clientId, if (on) "cohost" else "member"))

        fun setEveryone(on: Boolean) = client.send(Intents.settings(control = if (on) "everyone" else "host"))
        fun makeHost(clientId: String) = client.send(Intents.hostTransfer(clientId))

        /** Online members other than us: who the host seat can go to. */
        val others: List<Member> get() = members.filter { it.online && it.clientId != me }

        internal fun close() {
            closed = true
            sync?.dispose()
            sync = null
            screen?.adapter?.release()
            screen = null
            client.leave()
        }
    }
}
