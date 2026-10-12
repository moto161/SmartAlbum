package com.example.smartalbum.ui.gallery
import com.example.smartalbum.util.LogUtil
import com.example.smartalbum.util.ActivityAnimationUtil

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.smartalbum.R
import com.example.smartalbum.data.Photo
import com.example.smartalbum.databinding.ActivityPhotoViewBinding
import com.example.smartalbum.util.ExifUtil

class PhotoViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewBinding
    private lateinit var photos: ArrayList<Photo>
    private var currentPosition: Int = 0
    private var isInfoVisible = true
    /** 等待用户授权删除的 uri（Android 10+ 分区存储必须先弹系统授权框） */
    private var pendingDeleteUri: android.net.Uri? = null

    companion object {
        private const val EXTRA_PHOTOS = "extra_photos"
        private const val EXTRA_POSITION = "extra_position"
        private const val REQUEST_CODE_DELETE_MEDIA = 1002

        fun newIntent(context: Context, photos: ArrayList<Photo>, position: Int): Intent {
            return Intent(context, PhotoViewActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_PHOTOS, photos)
                putExtra(EXTRA_POSITION, position)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photos = intent.getParcelableArrayListExtra(EXTRA_PHOTOS) ?: arrayListOf()
        currentPosition = intent.getIntExtra(EXTRA_POSITION, 0)

        if (photos.isEmpty()) {
            ActivityAnimationUtil.finishWithAnimation(this)
            return
        }

        setupViewPager()
        updatePhotoInfo(currentPosition)

        binding.photoViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                updatePhotoInfo(position)
            }
        })

        binding.btnBack.setOnClickListener {
            ActivityAnimationUtil.finishWithAnimation(this)
        }

        binding.btnCopyLocation.setOnClickListener {
            copyLocation()
        }

        binding.btnOpenGallery.setOnClickListener {
            openInSystemGallery()
        }

        binding.btnShare.setOnClickListener {
            sharePhoto()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            window.decorView.setBackgroundColor(theme.getBackgroundColor())
        } catch (e: Exception) {
            android.util.Log.e("PhotoView", "应用主题失败: ${e.message}")
        }
    }

    private fun copyLocation() {
        val photo = photos[currentPosition]
        if (!photo.hasLocation) {
            Toast.makeText(this, "这张照片没有位置信息", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = NoLocationPhotosActivity.newIntent(
            this,
            photo.latitude ?: 0.0,
            photo.longitude ?: 0.0
        )
        ActivityAnimationUtil.startActivityWithAnimation(this, intent)
    }

    /**
     * 在系统相册中打开当前照片
     * 优先使用系统相册应用，避免弹出第三方应用选择器
     */
    private fun openInSystemGallery() {
        val photo = photos[currentPosition]
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(photo.uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 查询所有能处理图片的应用
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)

            // 系统相册包名优先级列表
            val systemGalleryPackages = listOf(
                "com.android.gallery3d",
                "com.miui.gallery",
                "com.huawei.photos",
                "com.oplus.photos",
                "com.vivo.gallery",
                "com.samsung.android.gallery3d",
                "com.google.android.apps.photos",
                "com.android.photos",
                "org.codeaurora.gallery",
                "com.motorola.gallery"
            )

            // 优先找系统相册
            var systemGalleryPackage: String? = null
            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                if (systemGalleryPackages.contains(pkg)) {
                    systemGalleryPackage = pkg
                    break
                }
            }

            // 如果没找到系统相册，找非第三方的应用（排除微信、QQ等）
            if (systemGalleryPackage == null) {
                val thirdPartyPackages = listOf(
                    "com.tencent.mm",
                    "com.tencent.mobileqq",
                    "com.tencent.tim",
                    "com.sina.weibo",
                    "com.zhihu.android",
                    "com.xingin.xhs"
                )
                for (info in resolveInfos) {
                    val pkg = info.activityInfo.packageName
                    if (!thirdPartyPackages.contains(pkg)) {
                        systemGalleryPackage = pkg
                        break
                    }
                }
            }

            if (systemGalleryPackage != null) {
                // 直接用系统相册打开，不弹出选择器
                intent.setPackage(systemGalleryPackage)
                startActivity(intent)
            } else {
                // 没有系统相册，用选择器
                startActivity(Intent.createChooser(intent, "打开照片"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开系统相册: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 分享当前照片
     */
    private fun sharePhoto() {
        val photo = photos[currentPosition]
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, photo.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享照片"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog() {
        val photo = photos[currentPosition]
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除照片")
            .setMessage("确定要删除这张照片吗？\n\n${photo.displayName}\n\n此操作不可撤销！")
            .setPositiveButton("删除") { _, _ ->
                deleteCurrentPhoto()
            }
            .setNegativeButton("取消", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    /**
     * 删除当前照片
     *
     * Android 10+ 启用分区存储后，直接 `contentResolver.delete(uri)` 会抛
     * RecoverableSecurityException（即使有 READ_MEDIA_IMAGES / MANAGE_MEDIA），
     * 必须先通过 MediaStore.createDeleteRequest / createTrashRequest 让用户授权。
     */
    private fun deleteCurrentPhoto() {
        val photo = photos.getOrNull(currentPosition) ?: return
        val uri = photo.uri

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                pendingDeleteUri = uri
                val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                } else {
                    // Android 10 没有 createDeleteRequest，退到「移入回收站」（用户可自行恢复）
                    MediaStore.createTrashRequest(contentResolver, listOf(uri), true)
                }
                startIntentSenderForResult(
                    request.intentSender, REQUEST_CODE_DELETE_MEDIA, null, 0, 0, 0
                )
                return
            } catch (e: Exception) {
                LogUtil.w("PhotoViewActivity", "申请删除授权失败，回退直接删除: ${e.message}")
                pendingDeleteUri = null
            }
        }
        performDelete(uri)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_DELETE_MEDIA) return
        val uri = pendingDeleteUri
        pendingDeleteUri = null
        if (resultCode == RESULT_OK && uri != null) {
            performDelete(uri)
        } else {
            Toast.makeText(this, "已取消删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performDelete(uri: android.net.Uri) {
        try {
            val rows = contentResolver.delete(uri, null, null)
            LogUtil.d("PhotoViewActivity", "删除 uri=$uri rows=$rows")

            // 按 uri 定位而不是 currentPosition：授权弹窗期间用户可能已经翻页
            val idx = photos.indexOfFirst { it.uri == uri }
            if (idx >= 0) photos.removeAt(idx)

            if (photos.isEmpty()) {
                Toast.makeText(this, "照片已删除", Toast.LENGTH_SHORT).show()
                ActivityAnimationUtil.finishWithAnimation(this)
                return
            }

            if (currentPosition >= photos.size) {
                currentPosition = photos.size - 1
            }

            binding.photoViewPager.adapter?.notifyDataSetChanged()
            binding.photoViewPager.setCurrentItem(currentPosition, false)
            updatePhotoInfo(currentPosition)

            Toast.makeText(this, "照片已删除", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            LogUtil.e("PhotoViewActivity", "删除失败", e)
            Toast.makeText(this, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    private fun setupViewPager() {
        val adapter = PhotoPagerAdapter(photos)
        binding.photoViewPager.adapter = adapter
        binding.photoViewPager.setCurrentItem(currentPosition, false)
    }

    private fun updatePhotoInfo(position: Int) {
        val photo = photos[position]
        binding.photoTitle.text = photo.displayName

        val latStr = String.format(Locale.US, "%.6f", photo.latitude)
        val lonStr = String.format(Locale.US, "%.6f", photo.longitude)
        val posStr = (position + 1).toString()
        val totalStr = photos.size.toString()

        val info = buildString {
            append(getString(R.string.taken_at, ExifUtil.formatDate(photo.dateTaken)))
            if (photo.hasLocation) {
                append("\n")
                append(getString(R.string.taken_at_location, "$latStr, $lonStr"))
            } else {
                append("\n")
                append(getString(R.string.no_gps))
            }
        }
        binding.photoInfo.text = info
        binding.photoIndicator.text = "$posStr / $totalStr"

        binding.btnCopyLocation.visibility = if (photo.hasLocation) View.VISIBLE else View.GONE
        // 相册、分享、删除按钮始终显示
        binding.btnOpenGallery.visibility = View.VISIBLE
        binding.btnShare.visibility = View.VISIBLE
        binding.btnDelete.visibility = View.VISIBLE
    }

    inner class PhotoPagerAdapter(private val photoList: List<Photo>) :
        RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ZoomableImageView = itemView.findViewById(R.id.photo_image)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_full, parent, false)
            return PhotoViewHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            val photo = photoList[position]
            // 2026-10-13：ViewHolder 复用时必须重置缩放/平移矩阵。
            // Glide 走 setImageDrawable，不会触发 ZoomableImageView 对 setImageResource 的重置，
            // 上一张的放大状态会残留到下一张（切图后图被推出可视区）。
            holder.imageView.resetScale()
            // 优化：先加载缩略图快速显示，再加载原图，添加占位图和磁盘缓存
            Glide.with(holder.imageView.context)
                .load(photo.uri)
                .fitCenter()
                .placeholder(R.drawable.bg_placeholder)
                .thumbnail(0.3f)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                .into(holder.imageView)

            // 点击照片切换信息栏显示/隐藏
            holder.imageView.setOnPhotoTapListener {
                toggleInfoVisibility()
            }

            // 下拉关闭大图查看
            holder.imageView.setOnSwipeDownListener {
                ActivityAnimationUtil.finishWithAnimation(this@PhotoViewActivity)
            }
        }

        private fun toggleInfoVisibility() {
            isInfoVisible = !isInfoVisible
            if (isInfoVisible) {
                // 显示信息栏
                binding.photoTitle.animate().alpha(1f).setDuration(200).start()
                binding.photoInfo.animate().alpha(1f).setDuration(200).start()
                binding.btnCopyLocation.animate().alpha(1f).setDuration(200).start()
                binding.photoIndicator.animate().alpha(1f).setDuration(200).start()
                binding.photoTitle.visibility = View.VISIBLE
                binding.photoInfo.visibility = View.VISIBLE
                binding.btnCopyLocation.visibility = View.VISIBLE
                binding.btnOpenGallery.visibility = View.VISIBLE
                binding.btnShare.visibility = View.VISIBLE
                binding.btnDelete.visibility = View.VISIBLE
                binding.photoIndicator.visibility = View.VISIBLE
            } else {
                // 隐藏信息栏
                binding.photoTitle.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.photoTitle.visibility = View.GONE
                }.start()
                binding.photoInfo.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.photoInfo.visibility = View.GONE
                }.start()
                binding.btnCopyLocation.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.btnCopyLocation.visibility = View.GONE
                }.start()
                binding.btnOpenGallery.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.btnOpenGallery.visibility = View.GONE
                }.start()
                binding.btnShare.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.btnShare.visibility = View.GONE
                }.start()
                binding.btnDelete.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.btnDelete.visibility = View.GONE
                }.start()
                binding.photoIndicator.animate().alpha(0f).setDuration(200).withEndAction {
                    binding.photoIndicator.visibility = View.GONE
                }.start()
            }
        }

        override fun getItemCount(): Int = photoList.size
    }
}
