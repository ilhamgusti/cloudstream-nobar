package com.lagradost.cloudstream3.nobar

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Nobar (watch together) settings and the guest identity on the syncroom server.
 * The guest token is kept so reconnects and restarts stay the same member (and keep
 * the host seat).
 */
class NobarPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    /** The user's own server, else the one baked into the build ("" = none). */
    var server: String
        get() = prefs.getString(KEY_SERVER, null)?.clean()?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.NOBAR_SERVER.clean()
        set(v) = prefs.edit {
            val t = v.clean()
            if (t.isEmpty() || t == BuildConfig.NOBAR_SERVER.clean()) remove(KEY_SERVER) else putString(KEY_SERVER, t)
        }

    var name: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        set(v) = prefs.edit { putString(KEY_NAME, v.trim().take(40)) }

    var lastRoom: String
        get() = prefs.getString(KEY_ROOM, "") ?: ""
        set(v) = prefs.edit { putString(KEY_ROOM, v) }

    /** https://x → wss://x/v1/ws */
    val wsUrl get() = server.replaceFirst(Regex("^http"), "ws") + "/v1/ws"

    /** Fetch (or renew) our guest token. Blocking: the SDK calls it on an IO thread. */
    @WorkerThread
    fun token(): String {
        val server = server
        check(server.isNotEmpty()) { "no nobar server configured" }
        val body = JSONObject().put("name", name)
        prefs.getString(KEY_TOKEN, null)?.let { body.put("token", it) } // keep the same identity
        val conn = URL("$server/v1/guest").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw IllegalStateException("syncroom guest HTTP $status")
            val token = JSONObject(text).getString("token")
            prefs.edit { putString(KEY_TOKEN, token) }
            return token
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val KEY_SERVER = "nobar_server"
        private const val KEY_NAME = "nobar_name"
        private const val KEY_ROOM = "nobar_room"
        private const val KEY_TOKEN = "nobar_token"

        private fun String.clean() = trim().trimEnd('/')

        /** Room codes: 6 characters, no look-alikes (no I/O/0/1). */
        const val CODE_LENGTH = 6
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun newRoomCode() = (1..CODE_LENGTH).map { CODE_CHARS[Random.nextInt(CODE_CHARS.length)] }.joinToString("")

        /** Room ids are [A-Za-z0-9_-]{1,64}; codes are typed by humans, so uppercase. */
        fun normalize(code: String) =
            code.trim().uppercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
    }
}
