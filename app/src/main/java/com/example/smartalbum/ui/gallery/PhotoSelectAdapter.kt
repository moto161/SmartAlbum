package com.example.smartalbum.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.smartalbum.data.Photo
import com.example.smartalbum.databinding.ItemPhotoSelectBinding

class PhotoSelectAdapter(
    private val photos: List<Photo>,
    private val onSelectionChanged: (Set<Int>) -> Unit
) : RecyclerView.Adapter<PhotoSelectAdapter.ViewHolder>() {

    private val selectedPositions = mutableSetOf<Int>()

    fun getSelectedPhotos(): List<Photo> {
        return selectedPositions.map { photos[it] }
    }

    fun clearSelection() {
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPhotoSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(photos[position], position)
    }

    override fun getItemCount(): Int = photos.size

    inner class ViewHolder(private val binding: ItemPhotoSelectBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: Photo, position: Int) {
            Glide.with(binding.root.context)
                .load(photo.uri)
                .centerCrop()
                .into(binding.photoImageView)

            val isSelected = selectedPositions.contains(position)
            binding.checkBox.isChecked = isSelected
            binding.selectionOverlay.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE

            binding.root.setOnClickListener {
                if (selectedPositions.contains(position)) {
                    selectedPositions.remove(position)
                } else {
                    selectedPositions.add(position)
                }
                notifyItemChanged(position)
                onSelectionChanged(selectedPositions)
            }
        }
    }
}
