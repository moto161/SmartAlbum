package com.example.smartalbum.ui.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.smartalbum.R
import com.example.smartalbum.data.Photo
import com.example.smartalbum.databinding.ItemPhotoBinding

class PhotoAdapter(
    private val photos: List<Photo>,
    private val onPhotoClick: (Photo, Int) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    // 图片加载配置：限制大小、使用缓存、加载缩略图
    //
    // 2026-10-10：删掉了手写的 LruCache<String, Bitmap>。
    // 原因：Glide 返回的 Bitmap 由 Glide 池化/回收管理，我们在 onResourceReady 里
    // 把它塞进自己的 LruCache 长期持有 —— Glide 之后可能复用或回收这块内存，
    // 于是同一个 Bitmap 被画到别的格子（花屏），严重时抛
    // "Cannot draw a recycled Bitmap"。缩略图缓存本来就该交给 Glide 自己的
    // 内存缓存 + 磁盘缓存，不要自己再拷一份。
    private val requestOptions = RequestOptions()
        .centerCrop()
        .override(200, 200) // 缩略图 200x200，兼顾清晰度与内存
        .placeholder(R.drawable.bg_placeholder)
        .error(R.drawable.bg_placeholder)
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // 只缓存处理后的图，减少磁盘占用

    inner class PhotoViewHolder(val binding: ItemPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: Photo, position: Int) {
            Glide.with(binding.photoImage)
                .load(photo.uri)
                .apply(requestOptions)
                .thumbnail(0.2f) // 先加载20%质量的缩略图，更快显示
                .into(binding.photoImage)

            binding.root.setOnClickListener { onPhotoClick(photo, position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(photos[position], position)
    }

    override fun getItemCount(): Int = photos.size

    // 释放资源
    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        // 必须清 photoImage 而不是 itemView：Glide 的请求挂在 ImageView 上，
        // clear(itemView) 清的是根布局，请求根本没被取消 —— 快速滑动时
        // 上一屏的图片回调会打到复用的格子上，表现就是「花屏/串图」。
        Glide.with(holder.binding.photoImage).clear(holder.binding.photoImage)
    }
}
