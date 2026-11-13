@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lagradost.cloudstream3.nobar

import androidx.annotation.MainThread
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.syncroom.MediaAdapter
import dev.syncroom.MediaEvent
import dev.syncroom.MediaRef
import kotlin.math.abs

/**
 * syncroom [MediaAdapter] over the player screen's ExoPlayer. Main thread only (the SDK
 * scheduler is the main looper).
 *
 * CS3IPlayer builds a new ExoPlayer for every link (episode, mirror, reload), so the
 * adapter doesn't own one: the player screen hands each new instance to [attach].
 *
 * @param currentId the room item id of what the screen is showing now (null = nothing shareable)
 * @param open switch the screen to [MediaRef]; false if this device can't play it
 */
@MainThread
class NobarAdapter(
    private val currentId: () -> String?,
    private val open: (MediaRef) -> Boolean,
) : MediaAdapter {
    private var exo: ExoPlayer? = null
    private var listener: ((MediaEvent) -> Unit)? = null

    /** Between load() and done(): buffering here is loading, not a stall. */
    private var loading = false
    /** The room item we're opening; a new ExoPlayer has to show up before it can be ready. */
    var opening: String? = null
        private set
    private var needFreshPlayer = false
    private var pending: Pending? = null
    private var wasReady = false
    private var stalled = false

    private class Pending(val id: String, val startAt: () -> Double, val autoplay: Boolean, val done: () -> Unit)

    /** The room just moved this player (remote play/pause/seek). */
    var onRemote: (() -> Unit)? = null

    private val exoListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (loading) return
            // Any cause (user, audio focus, headphones) is a play/pause the room should hear
            // about; the SDK filters out the ones it caused itself.
            listener?.invoke(if (playWhenReady) MediaEvent.PLAY else MediaEvent.PAUSE)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !loading) listener?.invoke(MediaEvent.SEEKED)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    if (tryFinish()) return
                    wasReady = true
                    if (stalled) {
                        stalled = false
                        listener?.invoke(MediaEvent.PLAYING)
                    }
                }

                Player.STATE_BUFFERING -> if (wasReady && !loading && !stalled) {
                    stalled = true
                    listener?.invoke(MediaEvent.WAITING)
                }

                Player.STATE_ENDED -> if (!loading) listener?.invoke(MediaEvent.ENDED)
                Player.STATE_IDLE -> wasReady = false
            }
        }
    }

    /** A new ExoPlayer (or null when the old one was released). */
    fun attach(player: ExoPlayer?) {
        if (player === exo) return
        exo?.removeListener(exoListener)
        exo = player
        wasReady = false
        stalled = false
        if (player == null) return
        needFreshPlayer = false
        player.addListener(exoListener)
        wasReady = player.playbackState == Player.STATE_READY
        tryFinish()
    }

    /**
     * The room let go of this player (left, or the screen detached): undo the rate nudge and
     * forget the pending load. The player stays tracked, so a later room can pick it up.
     */
    fun release() {
        exo?.let { if (abs(it.playbackParameters.speed - 1f) > 0.001f) it.setPlaybackSpeed(1f) }
        listener = null
        pending = null
        loading = false
        opening = null
        needFreshPlayer = false
        stalled = false
    }

    override fun load(media: MediaRef, startAt: () -> Double, autoplay: Boolean, done: () -> Unit) {
        loading = true
        stalled = false
        pending = Pending(media.id, startAt, autoplay, done)
        if (media.id != currentId()) {
            opening = media.id
            needFreshPlayer = true
            if (!open(media)) {
                // Can't play it here (missing extension, not a CloudStream item): stay out
                // of the barrier's way; the server stops waiting after startBarrierMs.
                pending = null
                loading = false
                opening = null
                return
            }
        }
        tryFinish()
    }

    /** Finish a pending load once the right item is ready to play. */
    private fun tryFinish(): Boolean {
        val p = pending ?: return false
        val player = exo ?: return false
        if (needFreshPlayer || p.id != currentId() || player.playbackState != Player.STATE_READY) return false
        pending = null
        player.seekTo((p.startAt() * 1000).toLong().coerceAtLeast(0))
        player.playWhenReady = p.autoplay
        loading = false
        opening = null
        wasReady = true
        // "Done" = can actually play from there: that's what the start barrier waits for.
        p.done()
        return true
    }

    override fun getTime(): Double? {
        val player = exo ?: return null
        if (loading || player.playbackState == Player.STATE_IDLE || player.currentMediaItem == null) return null
        return player.currentPosition / 1000.0
    }

    override fun isPaused() = exo?.playWhenReady != true
    override fun isBuffering() = exo?.playbackState == Player.STATE_BUFFERING

    override fun play(): Boolean {
        val player = exo ?: return true
        if (!player.playWhenReady) onRemote?.invoke()
        player.playWhenReady = true
        return true
    }

    override fun pause() {
        val player = exo ?: return
        if (player.playWhenReady) onRemote?.invoke()
        player.playWhenReady = false
    }

    override fun seek(t: Double) {
        val player = exo ?: return
        onRemote?.invoke()
        player.seekTo((t * 1000).toLong().coerceAtLeast(0))
    }

    override fun setRate(r: Double) {
        // Media3 keeps the pitch (time-stretch), so a ±5 % nudge is inaudible.
        val player = exo ?: return
        if (abs(player.playbackParameters.speed - r) > 0.001) player.setPlaybackSpeed(r.toFloat())
    }

    override fun setListener(l: ((MediaEvent) -> Unit)?) {
        listener = l
    }
}
