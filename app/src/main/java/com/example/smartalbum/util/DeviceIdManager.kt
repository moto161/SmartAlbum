package com.example.smartalbum.util

import android.content.Context
import java.util.UUID

/**
 * 设备ID管理
 * 第一次启动时生成唯一UUID，永久保存
 */
object DeviceIdManager {
    private const val PREFS_NAME = "device_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun getDeviceName(): String {
        return android.os.Build.MODEL
    }

    fun getDeviceModel(): String {
        return android.os.Build.DEVICE
    }
}
