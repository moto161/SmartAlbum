package com.example.smartalbum.model

/**
 * 位置数据模型
 */
data class LocationData(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val isHighAccuracy: Boolean,
    val time: String = ""
)

/**
 * 用户信息模型（管理面板用）
 */
data class UserInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val remark: String,
    val lastUpdate: String,
    val trailCount: Int
)

/**
 * 历史轨迹点
 */
data class TrailPoint(
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val isHighAccuracy: Boolean,
    val createdAt: String
)
