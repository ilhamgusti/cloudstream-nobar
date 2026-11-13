// Vendored from syncroom@c2c0b20 (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here.
package dev.syncroom

import org.json.JSONArray
import org.json.JSONObject

// Wire types (see src/core/types.ts in the syncroom repo). The core never
// reads MediaRef.meta — apps put provider ids, posters, stream hints there.

data class MediaRef(
    val id: String,
    val durationSec: Double? = null,
    val title: String? = null,
    val meta: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).apply {
        durationSec?.let { put("durationSec", it) }
        title?.let { put("title", it) }
        meta?.let { put("meta", it) }
    }

    companion object {
        fun parse(o: JSONObject?): MediaRef? = o?.let {
            MediaRef(it.getString("id"), it.dbl("durationSec"), it.str("title"), it.optJSONObject("meta"))
        }
    }
}

/**
 * Anchor line: the room is at [position] s at server time [anchorAt], moving
 * at [rate] while [playing] and nobody is in [waiting].
 */
data class Playback(
    val epoch: Int,
    val itemId: String?,
    val playing: Boolean,
    val position: Double,
    val anchorAt: Double,
    val rate: Double,
    val waiting: List<String>,
) {
    val advancing get() = playing && waiting.isEmpty()

    /** Position on the line at server time [now]. */
    fun positionAt(now: Double, durationSec: Double? = null): Double {
        val pos = if (advancing) position + maxOf(0.0, now - anchorAt) / 1000 * rate else position
        return if (durationSec != null && durationSec > 0) minOf(pos, durationSec) else pos
    }

    companion object {
        fun parse(o: JSONObject) = Playback(
            epoch = o.getInt("epoch"),
            itemId = o.str("itemId"),
            playing = o.optBoolean("playing"),
            position = o.optDouble("position", 0.0),
            anchorAt = o.optDouble("anchorAt", 0.0),
            rate = o.optDouble("rate", 1.0),
            waiting = o.optJSONArray("waiting").strings(),
        )
    }
}

/** [weak]: used up its waitForAll budget — the room no longer waits for it. */
data class Member(val clientId: String, val name: String, val joinedAt: Double, val online: Boolean, val buffering: Boolean, val weak: Boolean = false)

data class Roster(val hostId: String?, val members: List<Member>, val cohosts: List<String> = emptyList()) {
    companion object {
        fun parse(o: JSONObject) = Roster(
            o.str("hostId"),
            o.optJSONArray("members").objects().map {
                Member(it.getString("clientId"), it.optString("name"), it.optDouble("joinedAt", 0.0), it.optBoolean("online", true), it.optBoolean("buffering"), it.optBoolean("weak"))
            },
            o.optJSONArray("cohosts").strings(),
        )
    }
}

/** Server-wide sync behaviour (SYNCROOM_* env on the server). See SyncPolicy in src/core/types.ts. */
data class SyncPolicy(
    /** "host": the host's player is the reference. "server": the line is; the host is corrected too. */
    val reference: String = "host",
    val startBarrierMs: Long = 15_000,
    val startLeadMaxMs: Long = 3_000,
    val stallGraceMs: Long = 1_500,
    val stallCapMs: Long = 20_000,
    val hostStallCapMs: Long = 60_000,
    val waitBudgetMs: Long = 30_000,
    val waitBudgetWindowMs: Long = 300_000,
    val statsIntervalMs: Long = 5_000,
    val requestTtlMs: Long = 60_000,
) {
    companion object {
        fun parse(o: JSONObject?): SyncPolicy {
            if (o == null) return SyncPolicy()
            val d = SyncPolicy()
            fun l(k: String, def: Long) = if (o.has(k) && !o.isNull(k)) o.getLong(k) else def
            return SyncPolicy(
                reference = o.optString("reference", d.reference),
                startBarrierMs = l("startBarrierMs", d.startBarrierMs),
                startLeadMaxMs = l("startLeadMaxMs", d.startLeadMaxMs),
                stallGraceMs = l("stallGraceMs", d.stallGraceMs),
                stallCapMs = l("stallCapMs", d.stallCapMs),
                hostStallCapMs = l("hostStallCapMs", d.hostStallCapMs),
                waitBudgetMs = l("waitBudgetMs", d.waitBudgetMs),
                waitBudgetWindowMs = l("waitBudgetWindowMs", d.waitBudgetWindowMs),
                statsIntervalMs = l("statsIntervalMs", d.statsIntervalMs),
                requestTtlMs = l("requestTtlMs", d.requestTtlMs),
            )
        }
    }
}

/** A member asking the host / a co-host to do something. [action]: play | pause | seek | next | prev | load. */
data class ControlRequest(
    val id: String,
    val from: String,
    val name: String,
    val action: String,
    val position: Double?,
    val media: MediaRef?,
    val at: Double,
    val expiresAt: Double,
) {
    companion object {
        fun parse(o: JSONObject): ControlRequest {
            val a = o.getJSONObject("action")
            return ControlRequest(
                o.getString("id"), o.optString("from"), o.optString("name"), a.getString("type"),
                a.dbl("position"), MediaRef.parse(a.optJSONObject("media")), o.optDouble("at", 0.0), o.optDouble("expiresAt", 0.0),
            )
        }
    }
}

data class Settings(val control: String, val syncMode: String, val autoAdvance: Boolean, val custom: JSONObject?) {
    companion object {
        fun parse(o: JSONObject) = Settings(o.optString("control", "host"), o.optString("syncMode", "leader"), o.optBoolean("autoAdvance", true), o.optJSONObject("custom"))
    }
}

data class ChatMessage(val id: Long, val from: String, val name: String, val text: String, val at: Double) {
    companion object {
        fun parse(o: JSONObject) = ChatMessage(o.optLong("id"), o.optString("from"), o.optString("name"), o.optString("text"), o.optDouble("at", 0.0))
    }
}

data class QueueItem(val itemId: String, val media: MediaRef, val addedBy: String)

/** Local mirror of the room. Mutated only by RoomClient, on the scheduler thread. */
class RoomView(
    var current: MediaRef?,
    var playback: Playback,
    var queue: List<QueueItem>,
    var history: List<MediaRef>,
    var chat: List<ChatMessage>,
    var roster: Roster,
    var settings: Settings,
    var locked: Boolean,
    var autoAdvanceAt: Double?,
    var requests: List<ControlRequest> = emptyList(),
    /** Server policy; kept across replays (only snapshots carry it). */
    var sync: SyncPolicy = SyncPolicy(),
) {
    fun isCohost(clientId: String?) = clientId != null && clientId in roster.cohosts

    companion object {
        fun parse(state: JSONObject, roster: JSONObject, sync: JSONObject? = null) = RoomView(
            current = MediaRef.parse(state.optJSONObject("current")),
            playback = Playback.parse(state.getJSONObject("playback")),
            queue = parseQueue(state.optJSONArray("queue")),
            history = state.optJSONArray("history").objects().mapNotNull { MediaRef.parse(it) },
            chat = state.optJSONArray("chat").objects().map { ChatMessage.parse(it) },
            roster = Roster.parse(roster),
            settings = Settings.parse(state.optJSONObject("settings") ?: JSONObject()),
            locked = state.optJSONObject("access")?.optBoolean("locked") ?: false,
            autoAdvanceAt = state.dbl("autoAdvanceAt"),
            requests = state.optJSONArray("requests").objects().map { ControlRequest.parse(it) },
            sync = SyncPolicy.parse(sync),
        )
    }
}

internal fun parseQueue(a: JSONArray?) = a.objects().mapNotNull { q ->
    MediaRef.parse(q.optJSONObject("media"))?.let { QueueItem(q.getString("itemId"), it, q.optString("addedBy")) }
}

sealed interface RoomEvent {
    /** `cause`: play | pause | seek | sync | rate | ended | buffering | stall | resume | admin | restore */
    data class PlaybackChanged(val playback: Playback, val cause: String, val origin: String?) : RoomEvent
    data class NowPlaying(val current: MediaRef?, val playback: Playback, val origin: String?) : RoomEvent
    data class RosterChanged(val roster: Roster) : RoomEvent
    data class SettingsChanged(val settings: Settings) : RoomEvent
    data class Chat(val message: ChatMessage) : RoomEvent
    data class QueueChanged(val queue: List<QueueItem>) : RoomEvent
    data class RequestsChanged(val requests: List<ControlRequest>) : RoomEvent
    /** history, autoAdvance, log, access… — already applied to the mirror. */
    data class Other(val type: String, val raw: JSONObject) : RoomEvent

    companion object {
        fun parse(e: JSONObject): RoomEvent = when (val t = e.getString("type")) {
            "playback" -> PlaybackChanged(Playback.parse(e.getJSONObject("playback")), e.optString("cause"), e.str("origin"))
            "nowPlaying" -> NowPlaying(MediaRef.parse(e.optJSONObject("current")), Playback.parse(e.getJSONObject("playback")), e.str("origin"))
            "roster" -> RosterChanged(Roster.parse(e.getJSONObject("roster")))
            "settings" -> SettingsChanged(Settings.parse(e.getJSONObject("settings")))
            "chat" -> Chat(ChatMessage.parse(e.getJSONObject("message")))
            "queue" -> QueueChanged(parseQueue(e.optJSONArray("queue")))
            "requests" -> RequestsChanged(e.optJSONArray("requests").objects().map { ControlRequest.parse(it) })
            else -> Other(t, e)
        }
    }
}

/** Apply one ordered event to the mirror. */
fun RoomView.apply(e: RoomEvent) {
    when (e) {
        is RoomEvent.PlaybackChanged -> playback = e.playback
        is RoomEvent.NowPlaying -> {
            current = e.current
            playback = e.playback
        }
        is RoomEvent.RosterChanged -> roster = e.roster
        is RoomEvent.SettingsChanged -> settings = e.settings
        is RoomEvent.Chat -> chat = (chat + e.message).takeLast(200)
        is RoomEvent.QueueChanged -> queue = e.queue
        is RoomEvent.RequestsChanged -> requests = e.requests
        is RoomEvent.Other -> when (e.type) {
            "history" -> history = e.raw.optJSONArray("history").objects().mapNotNull { MediaRef.parse(it) }
            "autoAdvance" -> autoAdvanceAt = e.raw.dbl("at")
            "access" -> locked = e.raw.optBoolean("locked")
        }
    }
}

/** Intent builders (client → room). `at` = estimated server time the position was sampled. */
object Intents {
    private fun pb(type: String, epoch: Int) = JSONObject().put("type", type).put("epoch", epoch)
    private fun JSONObject.pos(position: Double, at: Double?) = put("position", position).apply { at?.let { put("at", it) } }

    fun play(epoch: Int, position: Double, at: Double? = null) = pb("play", epoch).pos(position, at)
    fun pause(epoch: Int, position: Double, at: Double? = null) = pb("pause", epoch).pos(position, at)
    fun seek(epoch: Int, position: Double, at: Double? = null) = pb("seek", epoch).pos(position, at)
    fun sync(epoch: Int, position: Double, at: Double? = null) = pb("sync", epoch).pos(position, at)
    fun rate(epoch: Int, rate: Double, position: Double, at: Double? = null) = pb("rate", epoch).put("rate", rate).pos(position, at)
    fun ended(epoch: Int) = pb("ended", epoch)
    /** Start barrier: our player has loaded and can play. */
    fun ready(epoch: Int, rttMs: Double? = null) = pb("ready", epoch).apply { rttMs?.let { put("rttMs", it.toLong()) } }
    fun buffering(epoch: Int, waiting: Boolean, position: Double? = null, at: Double? = null) =
        pb("buffering", epoch).put("waiting", waiting).apply { position?.let { put("position", it) }; at?.let { put("at", it) } }

    /** Start [media]; [position]/[at] start mid-item (the host was already watching). */
    fun load(media: MediaRef, position: Double? = null, at: Double? = null) =
        JSONObject().put("type", "load").put("media", media.toJson()).apply { position?.let { pos(it, at) } }
    fun next() = JSONObject().put("type", "next")
    fun prev() = JSONObject().put("type", "prev")
    fun queueAdd(media: MediaRef, atNext: Boolean = false) =
        JSONObject().put("type", "queue.add").put("media", media.toJson()).apply { if (atNext) put("at", "next") }
    fun queueRemove(itemId: String) = JSONObject().put("type", "queue.remove").put("itemId", itemId)
    fun chat(text: String) = JSONObject().put("type", "chat").put("text", text)
    fun settings(control: String? = null, syncMode: String? = null, autoAdvance: Boolean? = null) =
        JSONObject().put("type", "settings").put("patch", JSONObject().apply {
            control?.let { put("control", it) }
            syncMode?.let { put("syncMode", it) }
            autoAdvance?.let { put("autoAdvance", it) }
        })
    fun hostTransfer(to: String) = JSONObject().put("type", "host.transfer").put("to", to)
    fun kick(target: String, block: Boolean = false) = JSONObject().put("type", "member.kick").put("target", target).put("block", block)
    fun lock(pin: String? = null) = JSONObject().put("type", "access.lock").apply { pin?.let { put("pin", it) } }
    fun unlock() = JSONObject().put("type", "access.unlock")
    fun stats(stats: JSONObject) = JSONObject().put("type", "stats").put("stats", stats)
    /** Ask whoever controls playback. Build [action] with [RequestActions]. */
    fun request(action: JSONObject) = JSONObject().put("type", "request").put("action", action)
    fun resolveRequest(id: String, approve: Boolean) = JSONObject().put("type", "request.resolve").put("id", id).put("approve", approve)
    /** role: "cohost" | "member" (host only). */
    fun role(target: String, role: String) = JSONObject().put("type", "member.role").put("target", target).put("role", role)
    fun ephemeral(channel: String, payload: Any) = JSONObject().put("type", "ephemeral").put("channel", channel).put("payload", payload)
}

object RequestActions {
    fun play() = JSONObject().put("type", "play")
    fun pause() = JSONObject().put("type", "pause")
    fun seek(position: Double) = JSONObject().put("type", "seek").put("position", position)
    fun next() = JSONObject().put("type", "next")
    fun prev() = JSONObject().put("type", "prev")
    fun load(media: MediaRef) = JSONObject().put("type", "load").put("media", media.toJson())
}

// --- org.json helpers (JSON null → Kotlin null) ---
internal fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
internal fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
