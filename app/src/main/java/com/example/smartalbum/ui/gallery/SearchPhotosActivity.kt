package com.example.smartalbum.ui.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.smartalbum.data.Photo
import com.example.smartalbum.data.PhotoScanManager
import com.example.smartalbum.databinding.ActivitySearchPhotosBinding
import com.example.smartalbum.util.ActivityAnimationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchPhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchPhotosBinding
    private lateinit var adapter: PhotoAdapter
    private var allPhotos: List<Photo> = emptyList()

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, SearchPhotosActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        loadPhotos()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            binding.root.setBackgroundColor(theme.getBackgroundColor())
            binding.toolbar.setBackgroundColor(theme.getAccentColor())
            binding.toolbar.setTitleTextColor(theme.getButtonTextColor())
            binding.tvResultCount.setTextColor(theme.getTextColor())
        } catch (e: Exception) {
            android.util.Log.e("SearchPhotos", "应用主题失败: ${e.message}")
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            ActivityAnimationUtil.finishWithAnimation(this)
        }
    }

    private fun setupRecyclerView() {
        adapter = PhotoAdapter(emptyList()) { photo, position ->
            val intent = PhotoViewActivity.newIntent(this, ArrayList(allPhotos), position)
            startActivity(intent)
        }
        binding.searchResultRecycler.layoutManager = GridLayoutManager(this, 3)
        binding.searchResultRecycler.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { /* 不需要处理 */ }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterPhotos(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) { /* 不需要处理 */ }
        })

        binding.btnClear.setOnClickListener {
            binding.searchEditText.setText("")
        }
    }

    private fun loadPhotos() {
        lifecycleScope.launch {
            binding.tvResultCount.text = "正在加载照片..."
            val photos = withContext(Dispatchers.IO) {
                PhotoScanManager.getPhotos(this@SearchPhotosActivity) { _, _ -> }
            }
            allPhotos = photos
            binding.tvResultCount.text = "共 ${photos.size} 张照片，输入关键词开始搜索"
        }
    }

    private fun filterPhotos(query: String) {
        if (query.isBlank()) {
            adapter = PhotoAdapter(emptyList()) { photo, position ->
                val intent = PhotoViewActivity.newIntent(this, ArrayList(allPhotos), position)
                startActivity(intent)
            }
            binding.searchResultRecycler.adapter = adapter
            binding.emptyView.visibility = View.GONE
            binding.searchResultRecycler.visibility = View.VISIBLE
            binding.tvResultCount.text = "共 ${allPhotos.size} 张照片，输入关键词开始搜索"
            return
        }

        val filtered = allPhotos.filter {
            it.displayName.contains(query, ignoreCase = true)
        }

        adapter = PhotoAdapter(filtered) { photo, position ->
            val intent = PhotoViewActivity.newIntent(this, ArrayList(filtered), position)
            startActivity(intent)
        }
        binding.searchResultRecycler.adapter = adapter

        binding.tvResultCount.text = "找到 ${filtered.size} 张匹配的照片"

        if (filtered.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.searchResultRecycler.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.searchResultRecycler.visibility = View.VISIBLE
        }
    }
}
