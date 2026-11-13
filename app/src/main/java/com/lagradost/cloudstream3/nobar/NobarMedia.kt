package com.lagradost.cloudstream3.nobar

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import dev.syncroom.MediaRef
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Maps a CloudStream episode to a syncroom room item and back.
 *
 * A room item only has to let every member fetch the same links, and in CloudStream
 * that is `api.loadLinks(episode.data)`. So the item carries the provider name and
 * the episode's `data`, plus the ids that local watch progress is saved under. Every
 * member needs the same extension installed.
 */
object NobarMedia {
    private const val PREFIX = "cs3:"

    /** Server limit for a whole item is 16 KiB; keep room for the rest of the item. */
    private const val MAX_DATA_BYTES = 12 * 1024

    /** Stable across devices: same provider + same episode data gives the same id. */
    fun idOf(ep: ResultEpisode) = PREFIX + ep.apiName + ":" + sha1(ep.data).take(20)

    /** null when the episode can't be shared (its data is too large for a room item). */
    fun from(ep: ResultEpisode, durationSec: Double? = null): MediaRef? {
        if (ep.data.toByteArray().size > MAX_DATA_BYTES) return null
        val meta = JSONObject()
            .put("api", ep.apiName)
            .put("data", ep.data)
            .put("header", ep.headerName)
            .put("ep", ep.episode)
            .put("epId", ep.id)
            .put("parentId", ep.parentId)
            .put("type", ep.tvType.name)
        ep.name?.let { meta.put("name", it) }
        ep.season?.let { meta.put("season", it) }
        ep.seasonIndex?.let { meta.put("seasonIndex", it) }
        ep.poster?.takeIf { it.length < 1024 }?.let { meta.put("poster", it) }
        return MediaRef(idOf(ep), durationSec?.takeIf { it > 0 }, titleOf(ep), meta)
    }

    /** A playable episode for a room item, or null if the item didn't come from CloudStream. */
    fun episodeOf(media: MediaRef): ResultEpisode? {
        if (!media.id.startsWith(PREFIX)) return null
        val m = media.meta ?: return null
        val api = m.optString("api").takeIf { it.isNotEmpty() } ?: return null
        val data = m.optString("data").takeIf { m.has("data") } ?: return null
        return buildResultEpisode(
            headerName = m.optString("header", media.title ?: ""),
            name = m.optString("name").takeIf { m.has("name") },
            poster = m.optString("poster").takeIf { m.has("poster") },
            episode = m.optInt("ep", 0),
            seasonIndex = if (m.has("seasonIndex")) m.optInt("seasonIndex") else null,
            season = if (m.has("season")) m.optInt("season") else null,
            data = data,
            apiName = api,
            id = m.optInt("epId", data.hashCode()),
            index = 0,
            tvType = runCatching { TvType.valueOf(m.optString("type")) }.getOrDefault(TvType.Movie),
            parentId = m.optInt("parentId", 0),
        )
    }

    fun apiNameOf(media: MediaRef): String? = media.meta?.optString("api")?.takeIf { it.isNotEmpty() }

    private fun titleOf(ep: ResultEpisode): String {
        val se = when {
            ep.season != null -> "S${ep.season}:E${ep.episode}"
            ep.tvType.isMovieType() -> null
            else -> "E${ep.episode}"
        }
        return listOfNotNull(ep.headerName, se, ep.name?.takeIf { it != ep.headerName })
            .joinToString(" · ").take(500)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
