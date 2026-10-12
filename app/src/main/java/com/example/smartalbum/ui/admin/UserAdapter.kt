package com.example.smartalbum.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.smartalbum.databinding.ItemUserBinding
import com.example.smartalbum.model.UserInfo

class UserAdapter(
    private val onEditRemark: (UserInfo) -> Unit,
    private val onViewTrails: (UserInfo) -> Unit,
    private val onDelete: (UserInfo) -> Unit = {}
) : RecyclerView.Adapter<UserAdapter.ViewHolder>() {

    private var users = listOf<UserInfo>()

    fun submitList(newUsers: List<UserInfo>) {
        users = newUsers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(users[position])
    }

    override fun getItemCount() = users.size

    fun getUserAt(position: Int): UserInfo? = users.getOrNull(position)

    inner class ViewHolder(private val binding: ItemUserBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(user: UserInfo) {
            binding.tvRemark.text = user.remark.ifEmpty { "未命名用户" }
            binding.tvDeviceName.text = user.deviceName
            binding.tvAddress.text = user.address.ifEmpty { "未知位置" }
            binding.tvLatLng.text = "经纬度: %.6f, %.6f".format(user.latitude, user.longitude)
            binding.tvLastUpdate.text = "更新时间: ${user.lastUpdate}"
            binding.tvTrailCount.text = "轨迹: ${user.trailCount}条"

            binding.btnEditRemark.setOnClickListener { onEditRemark(user) }
            binding.btnViewTrails.setOnClickListener { onViewTrails(user) }
        }
    }
}
