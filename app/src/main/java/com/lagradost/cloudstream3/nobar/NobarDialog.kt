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
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.DialogNobarBinding
import dev.syncroom.RequestActions
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
        val inflater = LayoutInflater.from(activity)
        val binding = DialogNobarBinding.inflate(inflater)
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

        binding.nobarAskPause.setOnClickListener { Nobar.session?.request(RequestActions.pause()) }
        binding.nobarAskPlay.setOnClickListener { Nobar.session?.request(RequestActions.play()) }
        binding.nobarAskHost.setOnClickListener { Nobar.session?.request(RequestActions.host()) }

        binding.nobarLeave.setOnClickListener {
            val s = Nobar.session ?: return@setOnClickListener
            val others = s.others
            if (!s.isHost || others.isEmpty()) {
                Nobar.leave()
                return@setOnClickListener
            }
            // The host leaving: pick who gets the seat (or let the server pass it on).
            val items = others.map { it.name } + activity.getString(R.string.nobar_leave_only)
            AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setTitle(R.string.nobar_hand_over_title)
                .setItems(items.toTypedArray()) { _, which ->
                    val to = others.getOrNull(which)
                    if (to != null) Nobar.leaveHandingOver(to.clientId) else Nobar.leave()
                }
                .setNegativeButton(R.string.sort_cancel, null)
                .show()
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

            val joined = s.status == Status.JOINED
            binding.nobarAskRow.isVisible = joined && !s.isHost
            binding.nobarAskPause.isVisible = !s.canControl
            binding.nobarAskPlay.isVisible = !s.canControl

            // Requests: whoever controls playback answers them.
            val requests = s.answerable
            binding.nobarRequestsTitle.isVisible = requests.isNotEmpty()
            binding.nobarRequestList.removeAllViews()
            for (r in requests) {
                val row = inflater.inflate(R.layout.nobar_request_row, binding.nobarRequestList, false)
                row.findViewById<TextView>(R.id.nobar_request_text).text =
                    activity.getString(R.string.nobar_request_toast, r.name, Nobar.actionLabel(activity, r))
                row.findViewById<MaterialButton>(R.id.nobar_request_decline)
                    .setOnClickListener { s.resolve(r.id, false) }
                row.findViewById<MaterialButton>(R.id.nobar_request_approve)
                    .setOnClickListener { s.resolve(r.id, true) }
                binding.nobarRequestList.addView(row)
            }

            val list = binding.nobarMemberList
            list.removeAllViews()
            for (m in s.members.filter { it.online }) {
                val isCohost = m.clientId in s.cohosts
                val tags = listOfNotNull(
                    activity.getString(R.string.nobar_tag_host).takeIf { m.clientId == s.hostId },
                    activity.getString(R.string.nobar_tag_cohost).takeIf { isCohost && m.clientId != s.hostId },
                    activity.getString(R.string.nobar_tag_you).takeIf { m.clientId == s.me },
                    activity.getString(R.string.nobar_tag_buffering).takeIf { m.buffering },
                )
                val row = inflater.inflate(R.layout.nobar_member_row, list, false)
                row.findViewById<TextView>(R.id.nobar_member_initial).text =
                    m.name.trim().take(1).uppercase()
                row.findViewById<TextView>(R.id.nobar_member_name).text = m.name
                row.findViewById<TextView>(R.id.nobar_member_tags).apply {
                    text = tags.joinToString(", ")
                    isVisible = tags.isNotEmpty()
                }
                // Host tools: co-host on/off, hand over the host seat.
                if (s.isHost && m.clientId != s.me) {
                    row.isFocusable = true
                    row.setOnClickListener {
                        val items = arrayOf(
                            activity.getString(if (isCohost) R.string.nobar_remove_cohost else R.string.nobar_make_cohost),
                            activity.getString(R.string.nobar_make_host),
                        )
                        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                            .setTitle(m.name)
                            .setItems(items) { _, which ->
                                when (which) {
                                    0 -> s.setCohost(m.clientId, !isCohost)
                                    1 -> AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                                        .setMessage(activity.getString(R.string.nobar_make_host_confirm, m.name))
                                        .setPositiveButton(R.string.nobar_make_host) { _, _ -> s.makeHost(m.clientId) }
                                        .setNegativeButton(R.string.sort_cancel, null)
                                        .show()
                                }
                            }
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
