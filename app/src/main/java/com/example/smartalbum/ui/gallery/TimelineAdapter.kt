package com.example.smartalbum.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartalbum.data.Photo
import com.example.smartalbum.databinding.ItemTimelineGroupBinding

class TimelineAdapter(
    private val groups: List<TimelineGroup>,
    private val onPhotoClick: (List<Photo>, Int) -> Unit
) : RecyclerView.Adapter<TimelineAdapter.GroupViewHolder>() {

    inner class GroupViewHolder(val binding: ItemTimelineGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: TimelineGroup) {
            binding.tvDate.text = "${group.date}（${group.photos.size}张）"
            val photoAdapter = PhotoAdapter(group.photos) { photo, position ->
                onPhotoClick(group.photos, position)
            }
            binding.photoGrid.layoutManager = GridLayoutManager(binding.root.context, 3)
            binding.photoGrid.adapter = photoAdapter
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemTimelineGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size
}
