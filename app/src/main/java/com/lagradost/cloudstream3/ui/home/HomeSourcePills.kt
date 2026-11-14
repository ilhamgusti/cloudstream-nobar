package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.selectHomepage
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * Home's source row: every installed extension with a homepage as a pill (pinned ones first),
 * the selected one filled blush, and a last "all sources" pill that opens the full picker
 * (random, none, pinning, per-plugin settings).
 */
class HomeSourcePillAdapter(
    private val viewModel: HomeViewModel,
) : RecyclerView.Adapter<HomeSourcePillAdapter.Holder>() {
    class Holder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    private var names: List<String> = emptyList()
    private var selected: String? = null

    fun refresh(context: android.content.Context, selectedName: String?) {
        val pinned = DataStoreHelper.pinnedProviders.toList()
        val all = context.filterProviderByPreferredMedia().map { it.name }
        val ordered = pinned.filter { it in all } + all.filter { it !in pinned }
        // A selection outside the list (random, none) still shows up front so the row says where you are.
        val front = selectedName?.takeIf { it !in ordered && it != noneApi.name }
        val newNames = listOfNotNull(front) + ordered
        if (newNames != names || selectedName != selected) {
            names = newNames
            selected = selectedName
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = names.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.home_source_pill, parent, false) as Chip
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val chip = holder.chip
        val ctx = chip.context
        if (position == names.size) {
            chip.text = ctx.getString(R.string.home_all_sources)
            chip.isChecked = false
            chip.setChipIconResource(R.drawable.ic_baseline_filter_list_24)
            chip.isChipIconVisible = true
            chip.setOnClickListener {
                chip.isChecked = false
                ctx.selectHomepage(selected) { api ->
                    viewModel.loadAndCancel(api, forceReload = true, fromUI = true)
                }
            }
            return
        }
        val name = names[position]
        chip.isChipIconVisible = false
        chip.text = if (name == randomApi.name) ctx.getString(R.string.home_random) else name
        chip.isChecked = name == selected
        chip.setOnClickListener {
            // A chip toggles itself on click; the selection is owned by the view model instead.
            chip.isChecked = name == selected
            if (name != selected) viewModel.loadAndCancel(name, forceReload = true, fromUI = true)
        }
    }
}
