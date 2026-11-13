@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lagradost.cloudstream3.nobar

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.DialogNobarBinding
import dev.syncroom.Status
import dev.syncroom.SyncStatus

/** The nobar sheet: create/join a room, or see who's in it, share the invite and leave. */
object NobarDialog {
    const val SCHEME = "cloudstreamnobar"

    /** `cloudstreamnobar://CODE?server=…`: the server rides along so friends land on the same one. */
    fun inviteLink(code: String, server: String): String =
        "$SCHEME://$code?server=" + Uri.encode(server)

    /** Join from an invite link. Returns false if [link] isn't one. */
    fun handleLink(activity: Activity, link: String): Boolean {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return false
        if (uri.scheme != SCHEME) return false
        val code = NobarPrefs.normalize(uri.host ?: uri.schemeSpecificPart.trimStart('/').substringBefore('?'))
        if (code.isEmpty()) return false
        uri.getQueryParameter("server")?.takeIf { it.startsWith("http") }?.let {
            NobarPrefs(activity).server = it
        }
        if (Nobar.session?.code == code) return true
        Nobar.start(activity, code, create = false)
        return true
    }

    /** [fromPlayer]: the room starts with the video on screen. */
    fun show(activity: Activity, fromPlayer: Boolean = false, onDismiss: (() -> Unit)? = null) {
        val prefs = NobarPrefs(activity)
        val binding = DialogNobarBinding.inflate(LayoutInflater.from(activity))
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setView(binding.root)
            .create()

        binding.nobarCreate.setText(if (fromPlayer) R.string.nobar_create else R.string.nobar_create_empty)
        binding.nobarName.setText(prefs.name)
        binding.nobarCode.setText(prefs.lastRoom)
        binding.nobarServer.setText(prefs.server)

        fun saveInputs() {
            binding.nobarName.text?.toString()?.takeIf { it.isNotBlank() }?.let { prefs.name = it }
            prefs.server = binding.nobarServer.text?.toString() ?: ""
        }

        binding.nobarCreate.setOnClickListener {
            saveInputs()
            Nobar.start(activity, NobarPrefs.newRoomCode(), create = true)
        }

        fun join() {
            saveInputs()
            val code = NobarPrefs.normalize(binding.nobarCode.text?.toString() ?: "")
            if (code.isEmpty()) {
                showToast(activity, R.string.nobar_err_invalid, Toast.LENGTH_SHORT)
                return
            }
            Nobar.start(activity, code, create = false)
        }
        binding.nobarJoin.setOnClickListener { join() }
        binding.nobarCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) join()
            actionId == EditorInfo.IME_ACTION_GO
        }

        binding.nobarRoomCode.setOnClickListener {
            val s = Nobar.session ?: return@setOnClickListener
            val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clip?.setPrimaryClip(ClipData.newPlainText("nobar", s.code))
            showToast(activity, R.string.nobar_code_copied, Toast.LENGTH_SHORT)
        }

        binding.nobarShare.setOnClickListener {
            val s = Nobar.session ?: return@setOnClickListener
            val text = activity.getString(
                R.string.nobar_invite_text,
                s.code,
                inviteLink(s.code, prefs.server)
            )
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.nobar_share)))
        }

        binding.nobarLeave.setOnClickListener {
            Nobar.leave()
        }

        binding.nobarEveryone.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener // programmatic refresh
            Nobar.session?.setEveryone(checked)
        }

        fun render() {
            val s = Nobar.session
            binding.nobarSetup.isVisible = s == null
            binding.nobarRoom.isVisible = s != null
            if (s == null) return
            binding.nobarRoomCode.text = s.code
            binding.nobarStatus.text = statusText(activity, s)
            binding.nobarNowPlaying.text = s.current?.title
                ?.let { activity.getString(R.string.nobar_now_playing, it) } ?: ""
            binding.nobarNowPlaying.isVisible = s.current != null
            binding.nobarEveryone.isVisible = s.isHost
            binding.nobarEveryone.isChecked = s.everyone

            val list = binding.nobarMemberList
            list.removeAllViews()
            for (m in s.members.filter { it.online }) {
                val tags = listOfNotNull(
                    activity.getString(R.string.nobar_tag_host).takeIf { m.clientId == s.hostId },
                    activity.getString(R.string.nobar_tag_you).takeIf { m.clientId == s.me },
                    activity.getString(R.string.nobar_tag_buffering).takeIf { m.buffering },
                )
                val row = TextView(activity).apply {
                    text = if (tags.isEmpty()) m.name else "${m.name}  ·  ${tags.joinToString(", ")}"
                    setTextColor(binding.nobarStatus.currentTextColor)
                    textSize = 15f
                    setPadding(0, 8, 0, 8)
                }
                if (s.isHost && m.clientId != s.me) {
                    row.isFocusable = true
                    row.setBackgroundResource(android.R.drawable.list_selector_background)
                    row.setOnClickListener {
                        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                            .setMessage(activity.getString(R.string.nobar_make_host_confirm, m.name))
                            .setPositiveButton(R.string.nobar_make_host) { _, _ -> s.makeHost(m.clientId) }
                            .setNegativeButton(R.string.sort_cancel, null)
                            .show()
                    }
                }
                list.addView(row)
            }
        }

        render()
        val unsubscribe = Nobar.addListener { if (dialog.isShowing) render() }
        dialog.setOnDismissListener(DialogInterface.OnDismissListener {
            unsubscribe()
            onDismiss?.invoke()
        })
        dialog.show()
    }

    private fun statusText(ctx: Context, s: Nobar.Session): String = when (s.status) {
        Status.CONNECTING -> ctx.getString(R.string.nobar_status_connecting)
        Status.RECONNECTING -> ctx.getString(R.string.nobar_status_reconnecting)
        Status.CLOSED -> s.error ?: ""
        Status.JOINED -> if (!s.playerOpen) {
            ctx.getString(R.string.nobar_status_player_closed)
        } else when (s.syncStatus) {
            SyncStatus.IDLE -> ctx.getString(R.string.nobar_status_loading)
            SyncStatus.SYNCED -> ctx.getString(R.string.nobar_status_synced)
            SyncStatus.DRIFTING, SyncStatus.SEEKING -> ctx.getString(R.string.nobar_status_catching_up)
            SyncStatus.WAITING -> ctx.getString(
                R.string.nobar_status_waiting,
                s.waitingFor.joinToString(", ") { s.nameOf(it) }
            )
            SyncStatus.LOCAL_PAUSE -> ctx.getString(R.string.nobar_status_paused)
            SyncStatus.NEEDS_GESTURE -> ctx.getString(R.string.nobar_status_needs_play)
            SyncStatus.STRUGGLING -> ctx.getString(R.string.nobar_status_struggling)
        }
    }
}
