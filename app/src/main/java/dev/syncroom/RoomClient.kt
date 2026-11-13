// Vendored from syncroom@c2c0b20 (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here.
package dev.syncroom

import org.json.JSONObject
import java.net.URLEncoder

// WebSocket transport + a local mirror of the room. Handles reconnect
// (exponential backoff), resume (replay missed events by seq, or a fresh
// snapshot), gap detection, clock-sync pings and acked intents.

enum class Status { CONNECTING, JOINED, RECONNECTING, CLOSED }

data class Ack(val ok: Boolean, val reason: String? = null)

/** All callbacks run on the scheduler thread. */
interface RoomListener {
    fun onStatus(status: Status) {}
    /** Mirror replaced wholesale (join / resync). */
    fun onSnapshot(view: RoomView) {}
    /** One ordered event, already applied to the mirror. */
    fun onEvent(event: RoomEvent) {}
    /** pin | kicked | ephemeral — addressed to us only. */
    fun onPrivate(event: JSONObject) {}
    /** pin-required | wrong-pin | limited | blocked | forbidden | invalid | not-found. Retry with [RoomClient.rejoin]. */
    fun onJoinError(reason: String) {}
    fun onKicked(blocked: Boolean) {}
}

/** The surface SyncController needs (fakeable in tests). */
interface RoomLink {
    val clock: ClockSync
    val view: RoomView?
    val clientId: String?
    val status: Status
    fun addListener(l: RoomListener): () -> Unit
    fun send(intent: JSONObject, onAck: ((Ack) -> Unit)? = null)
    /** Fire-and-forget (no ack) — telemetry. */
    fun notify(intent: JSONObject)
}

class RoomClient(
    private val url: String,
    private val room: String,
    private val name: String,
    /** Blocking; runs on [Scheduler.io] before every (re)connect — return a fresh token. */
    private val token: () -> String,
    private val scheduler: Scheduler,
    private val transport: Transport = OkHttpTransport(),
    private var pin: String? = null,
    /** false = join only an existing room: the first join fails with "not-found" instead of creating it. */
    private val create: Boolean = true,
) : RoomLink {
    override val clock = ClockSync(localNow = scheduler::now)
    override var view: RoomView? = null
        private set
    override var clientId: String? = null
        private set
    override var status = Status.CONNECTING
        private set

    val isHost get() = view != null && view?.roster?.hostId == clientId

    /** Latest sync telemetry per member (drift, status, rttMs, app extras). Not sequenced. */
    val memberStats = HashMap<String, JSONObject>()

    private val listeners = LinkedHashSet<RoomListener>()
    private var socket: Transport.Socket? = null
    private var instance: String? = null
    private var seq = 0L
    private var backoff = 500L
    private var closed = false
    private var nextId = 1
    private val pending = HashMap<Int, Pair<(Ack) -> Unit, Cancellable>>()
    private val pingTimers = ArrayList<Cancellable>()
    /** The 20 s ping timer (replaced each round, so nothing accumulates). */
    private var pingEvery: Cancellable? = null

    init {
        scheduler.post { connect() }
    }

    override fun addListener(l: RoomListener): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun emit(fn: (RoomListener) -> Unit) {
        for (l in listeners.toList()) {
            try {
                fn(l)
            } catch (e: Throwable) {
                System.err.println("[syncroom] listener: $e")
            }
        }
    }

    private fun setStatus(s: Status) {
        if (status == s) return
        status = s
        emit { it.onStatus(s) }
    }

    private fun connect() {
        if (closed) return
        scheduler.io {
            val t = runCatching { token() }.getOrNull()
            scheduler.post {
                if (closed) return@post
                if (t == null) return@post scheduleReconnect()
                open(t)
            }
        }
    }

    private fun open(token: String) {
        lateinit var me: Transport.Socket
        me = transport.connect("$url?token=${URLEncoder.encode(token, "UTF-8")}", object : Transport.Listener {
            override fun onOpen() = scheduler.post {
                if (socket !== me) return@post
                backoff = 500
                sendRaw(JSONObject().put("t", "join").put("room", room).put("name", name).apply {
                    pin?.let { put("pin", it) }
                    if (!create && instance == null) put("create", false)
                    instance?.let { put("resume", JSONObject().put("instance", it).put("seq", seq)) }
                })
                startPings()
            }
            override fun onMessage(text: String) = scheduler.post { if (socket === me) handleMessage(text) }
            override fun onClosed(code: Int, reason: String) = scheduler.post { if (socket === me) onSocketClosed(code, reason) }
        })
        socket = me
    }

    private fun onSocketClosed(code: Int, reason: String) {
        socket = null
        stopPings()
        failPending("disconnected")
        if (code == 4001) return kicked(reason == "blocked")
        scheduleReconnect()
    }

    /**
     * Kicked: stop for good. The server sends a private `kicked` event and then
     * closes with 4001; whichever arrives first closes us, so onKicked fires once.
     * (Some WebSocket stacks lose one of the two: Linux FoundationNetworking
     * rewrites 4001 to 1003 and can drop the frame before a close.)
     */
    private fun kicked(blocked: Boolean) {
        closed = true
        stopPings()
        failPending("disconnected")
        val s = socket
        socket = null
        s?.close()
        setStatus(Status.CLOSED)
        emit { it.onKicked(blocked) }
    }

    private fun failPending(reason: String) {
        val ps = pending.values.toList()
        pending.clear()
        for ((cb, timer) in ps) {
            timer.cancel()
            cb(Ack(false, reason))
        }
    }

    private fun scheduleReconnect() {
        if (closed) return
        setStatus(Status.RECONNECTING)
        val wait = backoff + (Math.random() * 250).toLong()
        backoff = minOf(backoff * 2, 15_000)
        scheduler.schedule(wait) { connect() }
    }

    private fun sendRaw(m: JSONObject) {
        socket?.send(m.toString())
    }

    private fun startPings() {
        stopPings()
        val ping = { sendRaw(JSONObject().put("t", "ping").put("t0", clock.local())) }
        ping()
        // 4 quick samples at connect, then one every 20 s.
        for (i in 1..3) pingTimers += scheduler.schedule(250L * i, ping)
        fun every() {
            pingEvery = scheduler.schedule(20_000) {
                ping()
                every()
            }
        }
        every()
    }

    private fun stopPings() {
        pingTimers.forEach { it.cancel() }
        pingTimers.clear()
        pingEvery?.cancel()
        pingEvery = null
    }

    private fun handleMessage(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (m.optString("t")) {
            "pong" -> clock.add(m.getDouble("t0"), m.getDouble("serverTime"), clock.local())
            "joined" -> {
                clientId = m.getString("clientId")
                instance = m.getString("instance")
                val snap = m.optJSONObject("snapshot")
                if (snap != null) applySnapshot(snap)
                else m.optJSONArray("replay").objects().forEach { applyEnvelope(it) }
                setStatus(Status.JOINED)
            }
            "snapshot" -> applySnapshot(m.getJSONObject("snapshot"))
            "join-error" -> {
                closed = true // app decides: rejoin(pin) or give up
                val reason = m.optString("reason")
                socket?.close()
                socket = null
                stopPings()
                setStatus(Status.CLOSED)
                emit { it.onJoinError(reason) }
            }
            "event" -> applyEnvelope(m.getJSONObject("env"))
            "private" -> {
                val e = m.getJSONObject("event")
                if (e.optString("type") == "stats") e.optJSONObject("stats")?.let { memberStats[e.getString("from")] = it.put("receivedAt", clock.local()) }
                emit { it.onPrivate(e) }
                if (e.optString("type") == "kicked") kicked(e.optBoolean("blocked"))
            }
            "ack" -> pending.remove(m.getInt("id"))?.let { (cb, timer) ->
                timer.cancel()
                cb(if (m.optBoolean("ok")) Ack(true) else Ack(false, m.str("reason")))
            }
        }
    }

    private fun applySnapshot(s: JSONObject) {
        instance = s.getString("instance")
        seq = s.getLong("seq")
        if (!clock.ready) clock.seed(s.getDouble("serverTime")) // until the first pong
        val v = RoomView.parse(s.getJSONObject("state"), s.getJSONObject("roster"), s.optJSONObject("sync"))
        view = v
        emit { it.onSnapshot(v) }
    }

    private fun applyEnvelope(env: JSONObject) {
        val v = view ?: return
        if (env.getString("instance") != instance) return
        val n = env.getLong("seq")
        if (n <= seq) return // duplicate
        if (n != seq + 1) {
            // Gap: missed events — fetch a fresh snapshot instead of guessing.
            sendRaw(JSONObject().put("t", "resync"))
            return
        }
        seq = n
        val e = RoomEvent.parse(env.getJSONObject("event"))
        v.apply(e)
        emit { it.onEvent(e) }
    }

    /** Send an intent; [onAck] gets the server's verdict (or disconnected/timeout). */
    override fun send(intent: JSONObject, onAck: ((Ack) -> Unit)?) {
        val s = socket
        if (s == null || status != Status.JOINED) {
            onAck?.invoke(Ack(false, "disconnected"))
            return
        }
        val id = nextId++
        if (onAck != null) {
            val timeout = maxOf(8000.0, clock.rtt * 3).toLong()
            pending[id] = onAck to scheduler.schedule(timeout) { pending.remove(id)?.first?.invoke(Ack(false, "timeout")) }
        }
        s.send(JSONObject().put("t", "intent").put("id", id).put("intent", intent).toString())
    }

    override fun notify(intent: JSONObject) {
        if (status == Status.JOINED) socket?.send(JSONObject().put("t", "intent").put("intent", intent).toString())
    }

    /** Ask whoever controls playback (host / co-host). See [RequestActions]. */
    fun request(action: JSONObject, onAck: ((Ack) -> Unit)? = null) = send(Intents.request(action), onAck)

    /** Retry after a join error (e.g. with the PIN the user typed). */
    fun rejoin(pin: String? = this.pin) {
        this.pin = pin
        closed = false
        setStatus(Status.CONNECTING)
        connect()
    }

    /** Leave for good (a dropped connection keeps the seat during the grace window). */
    fun leave() {
        sendRaw(JSONObject().put("t", "leave"))
        close()
    }

    fun close() {
        closed = true
        stopPings()
        failPending("closed")
        val s = socket
        socket = null
        s?.close()
        setStatus(Status.CLOSED)
    }
}
