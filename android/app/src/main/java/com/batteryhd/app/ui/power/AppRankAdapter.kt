package com.batteryhd.app.ui.power

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.batteryhd.app.databinding.ItemAppBinding

/** 耗电排行列表适配器 */
class AppRankAdapter : RecyclerView.Adapter<AppRankAdapter.VH>() {

    private val items = mutableListOf<Pair<String, Float>>()

    fun submit(list: List<Pair<String, Float>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Pair<String, Float>) {
            binding.tvName.text = item.first
            binding.tvPercent.text = "%.1f%%".format(item.second)
        }
    }
}
