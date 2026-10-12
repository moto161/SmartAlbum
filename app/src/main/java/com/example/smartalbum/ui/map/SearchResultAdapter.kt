package com.example.smartalbum.ui.map
import com.example.smartalbum.util.LogUtil

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.services.core.PoiItem
import com.example.smartalbum.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onItemClick: (PoiItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private var items = listOf<PoiItem>()

    fun submitList(newItems: List<PoiItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PoiItem) {
            try {
                binding.tvName.text = item.title ?: "未知地点"
                val snippet = item.snippet ?: ""
                val cityName = item.cityName ?: ""
                val adName = item.adName ?: ""
                val address = buildString {
                    if (snippet.isNotEmpty()) append(snippet)
                    if (cityName.isNotEmpty()) {
                        if (isNotEmpty()) append(" - ")
                        append(cityName)
                    }
                    if (adName.isNotEmpty()) append(adName)
                }
                binding.tvAddress.text = address.ifEmpty { "暂无地址信息" }
                binding.root.setOnClickListener { onItemClick(item) }
            } catch (e: Exception) {
                LogUtil.e("SearchResultAdapter", "异常", e)
                binding.tvName.text = "未知地点"
                binding.tvAddress.text = "暂无地址信息"
            }
        }
    }
}
