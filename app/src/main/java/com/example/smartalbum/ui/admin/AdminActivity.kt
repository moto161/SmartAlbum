package com.example.smartalbum.ui.admin
import com.example.smartalbum.util.ActivityAnimationUtil

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartalbum.api.LocationApiClient
import com.example.smartalbum.databinding.ActivityAdminBinding
import com.example.smartalbum.model.UserInfo
import com.example.smartalbum.ui.trail.TrailMapActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var userAdapter: UserAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置返回按钮
        binding.toolbar.setNavigationOnClickListener { ActivityAnimationUtil.finishWithAnimation(this) }

        // 初始化列表
        userAdapter = UserAdapter(
            onEditRemark = { user -> showEditRemarkDialog(user) },
            onViewTrails = { user -> showTrailsDialog(user) },
            onDelete = { user -> showDeleteConfirmDialog(user) }
        )
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = userAdapter

        // 左滑删除
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val user = userAdapter.getUserAt(position)
                if (user != null) {
                    // 先恢复滑动，等确认后再删除
                    userAdapter.notifyItemChanged(position)
                    // 保存user对象，避免列表刷新后position失效
                    showDeleteConfirmDialog(user)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvUsers)

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener {
            loadUserList()
        }

        // 加载数据
        loadUserList()
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
            binding.tvTotalUsers.setTextColor(theme.getTextColor())
        } catch (e: Exception) {
            android.util.Log.e("Admin", "应用主题失败: ${e.message}")
        }
    }

    private fun loadUserList() {
        binding.swipeRefresh.isRefreshing = true
        // 2026-10-10：裸 Thread 迁移到 lifecycleScope 协程，Activity 销毁时自动取消，避免 handler.post 操作已 finish 的 Activity
        lifecycleScope.launch {
            try {
                val users = withContext(Dispatchers.IO) { LocationApiClient.getUserList() }
                // 按deviceName去重，相同设备名只保留第一个
                val uniqueUsers = users.distinctBy { it.deviceName }
                userAdapter.submitList(uniqueUsers)
                binding.tvTotalUsers.text = "共 ${uniqueUsers.size} 个用户"
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                binding.tvLastRefresh.text = "最后刷新: $timeStr"
                binding.swipeRefresh.isRefreshing = false
                if (users.isEmpty()) {
                    Toast.makeText(this@AdminActivity, "暂无用户数据", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@AdminActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditRemarkDialog(user: UserInfo) {
        val input = EditText(this).apply {
            setText(user.remark)
            hint = "输入备注名称"
            setPadding(40, 30, 40, 30)
        }
        val container = LinearLayout(this).apply {
            setPadding(40, 20, 40, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("编辑备注")
            .setMessage("设备: ${user.deviceName}")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val newRemark = input.text.toString().trim()
                updateRemark(user.deviceId, newRemark)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateRemark(deviceId: String, remark: String) {
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) { LocationApiClient.updateRemark(deviceId, remark) }
                if (success) {
                    Toast.makeText(this@AdminActivity, "备注更新成功", Toast.LENGTH_SHORT).show()
                    loadUserList()
                } else {
                    Toast.makeText(this@AdminActivity, "备注更新失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AdminActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showTrailsDialog(user: UserInfo) {
        val intent = android.content.Intent(this, TrailMapActivity::class.java).apply {
            putExtra(TrailMapActivity.EXTRA_DEVICE_ID, user.deviceId)
            putExtra(TrailMapActivity.EXTRA_DEVICE_NAME, user.remark.ifEmpty { user.deviceName })
        }
        ActivityAnimationUtil.startActivityWithAnimation(this, intent)
    }

    private fun showDeleteConfirmDialog(user: UserInfo) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除设备「${user.remark.ifEmpty { user.deviceName }}」吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteUser(user)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteUser(user: UserInfo) {
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) { LocationApiClient.deleteUser(user.deviceId) }
                if (success) {
                    Toast.makeText(this@AdminActivity, "删除成功", Toast.LENGTH_SHORT).show()
                    loadUserList()
                } else {
                    Toast.makeText(this@AdminActivity, "删除失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AdminActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
