package com.example.smartalbum.ui.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.smartalbum.data.Photo
import com.example.smartalbum.data.PhotoScanManager
import com.example.smartalbum.databinding.ActivityTimelineBinding
import com.example.smartalbum.util.ActivityAnimationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, TimelineActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadTimeline()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            ActivityAnimationUtil.finishWithAnimation(this)
        }
    }

    private fun loadTimeline() {
        lifecycleScope.launch {
            binding.loadingView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE

            val photos = withContext(Dispatchers.IO) {
                PhotoScanManager.getPhotos(this@TimelineActivity) { _, _ -> }
            }

            binding.loadingView.visibility = View.GONE

            if (photos.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                return@launch
            }

            // 按日期分组（yyyy年MM月dd日）
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
            dateFormat.timeZone = TimeZone.getDefault()

            val grouped = photos
                .filter { it.dateTaken > 0 }
                .groupBy { dateFormat.format(Date(it.dateTaken)) }
                .toSortedMap(compareByDescending { it })

            val groups = grouped.map { (date, photoList) ->
                TimelineGroup(date, photoList)
            }

            val adapter = TimelineAdapter(groups) { photoList: List<com.example.smartalbum.data.Photo>, position: Int ->
                val intent = PhotoViewActivity.newIntent(this@TimelineActivity, ArrayList(photoList), position)
                startActivity(intent)
            }

            binding.timelineRecycler.layoutManager = LinearLayoutManager(this@TimelineActivity)
            binding.timelineRecycler.adapter = adapter
        }
    }
}
