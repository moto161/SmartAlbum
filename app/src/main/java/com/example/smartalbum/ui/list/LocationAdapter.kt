package com.example.smartalbum.ui.list

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.smartalbum.R
import com.example.smartalbum.data.LocationGroup
import com.example.smartalbum.databinding.ItemLocationBinding
import com.example.smartalbum.util.ExifUtil

class LocationAdapter(
    private val onItemClick: (LocationGroup) -> Unit
) : ListAdapter<LocationGroup, LocationAdapter.LocationViewHolder>(DiffCallback()) {

    inner class LocationViewHolder(private val binding: ItemLocationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: LocationGroup) {
            binding.locationName.text = group.locationName
            binding.photoCount.text = itemView.context.getString(R.string.photo_count, group.photoCount)

            // 显示日期范围
            val dateRange = buildString {
                group.earliestDate?.let { append(ExifUtil.formatDateOnly(it)) }
                append(" ~ ")
                group.latestDate?.let { append(ExifUtil.formatDateOnly(it)) }
            }
            binding.dateRange.text = dateRange

            // 加载封面图
            group.coverPhoto?.let { photo ->
                Glide.with(binding.ivThumbnail.context)
                    .load(photo.uri)
                    .centerCrop()
                    .placeholder(R.drawable.bg_placeholder)
                    .into(binding.ivThumbnail)
            }

            binding.root.setOnClickListener { onItemClick(group) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocationViewHolder {
        val binding = ItemLocationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LocationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LocationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<LocationGroup>() {
        override fun areItemsTheSame(oldItem: LocationGroup, newItem: LocationGroup): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LocationGroup, newItem: LocationGroup): Boolean {
            return oldItem == newItem
        }
    }
}
