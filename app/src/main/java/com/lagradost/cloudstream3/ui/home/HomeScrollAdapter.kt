package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.HomeScrollViewBinding
import com.lagradost.cloudstream3.databinding.HomeScrollViewTvBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

class HomeScrollAdapter(
    val callback: ((View, Int, LoadResponse) -> Unit)
) : NoStateAdapter<LoadResponse>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.uniqueUrl == b.uniqueUrl && a.name == b.name
})) {
    var hasMoreItems: Boolean = false

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (isLayout(TV or EMULATOR)) {
            HomeScrollViewTvBinding.inflate(inflater, parent, false)
        } else {
            HomeScrollViewBinding.inflate(inflater, parent, false)
        }

        return ViewHolderState(binding)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is HomeScrollViewBinding -> {
                clearImage(binding.homeScrollPreview)
            }

            is HomeScrollViewTvBinding -> {
                clearImage(binding.homeScrollPreview)
            }
        }
    }

    override fun onBindContent(
        holder: ViewHolderState<Any>,
        item: LoadResponse,
        position: Int,
    ) {
        val binding = holder.view

        val posterUrl = item.backgroundPosterUrl ?: item.posterUrl

        when (binding) {
            is HomeScrollViewBinding -> {
                binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders)
                binding.homeScrollPreviewTags.apply {
                    val context = binding.root.context
                    // "Series · 2026 · ★ 9.0", then the first genres when there is room.
                    val meta = listOfNotNull(
                        context.getString(typeLabel(item.type)),
                        item.year?.toString(),
                        item.score?.toStringNull(0.1, 10, 1, false)?.let { "\u2605 $it" },
                    ) + (item.tags?.take(2) ?: emptyList())
                    text = meta.joinToString(" \u00B7 ")
                    isGone = meta.isEmpty()
                    maxLines = 1
                }
                binding.homeScrollPreviewTitle.text = item.name.html()

                bindLogo(
                    url = item.logoUrl,
                    headers = item.posterHeaders,
                    titleView = binding.homeScrollPreviewTitle,
                    logoView = binding.homePreviewLogo
                )
            }

            is HomeScrollViewTvBinding -> {
                binding.homeScrollPreview.isFocusable = false
                binding.homeScrollPreview.setOnClickListener { view ->
                    callback.invoke(view ?: return@setOnClickListener, position, item)
                }
                binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders)
            }
        }
    }
}

/** Short type label for the hero's meta line ("Series · 2026 · ★ 9.0"). */
private fun typeLabel(type: TvType): Int = when (type) {
    TvType.Movie, TvType.AnimeMovie -> R.string.movies_singular
    TvType.TvSeries -> R.string.tv_series_singular
    TvType.Anime -> R.string.anime_singular
    TvType.OVA -> R.string.ova_singular
    TvType.Cartoon -> R.string.cartoons_singular
    TvType.Documentary -> R.string.documentaries_singular
    TvType.AsianDrama -> R.string.asian_drama_singular
    TvType.Live -> R.string.live_singular
    TvType.Torrent -> R.string.torrent_singular
    TvType.NSFW -> R.string.nsfw_singular
    TvType.Music -> R.string.music_singular
    TvType.AudioBook -> R.string.audio_book_singular
    TvType.CustomMedia -> R.string.custom_media_singular
    else -> R.string.other_singular
}
