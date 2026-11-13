// Vendored from syncroom@6b4705e (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here.
package dev.syncroom

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pluggable WebSocket. Listener callbacks may arrive on any thread. */
interface Transport {
    fun connect(url: String, listener: Listener): Socket

    interface Socket {
        fun send(text: String): Boolean
        fun close(code: Int = 1000, reason: String = "")
    }

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        /** Called exactly once per socket. */
        fun onClosed(code: Int, reason: String)
    }
}

class OkHttpTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : Transport {
    override fun connect(url: String, listener: Transport.Listener): Transport.Socket {
        val closed = AtomicBoolean(false)
        fun closedOnce(code: Int, reason: String) {
            if (closed.compareAndSet(false, true)) listener.onClosed(code, reason)
        }
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closedOnce(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedOnce(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closedOnce(1006, t.message ?: "")
        })
        return object : Transport.Socket {
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int, reason: String) {
                ws.close(code, reason)
                closedOnce(code, reason)
            }
        }
    }
}
