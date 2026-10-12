package com.example.smartalbum.cache
import com.example.smartalbum.util.LogUtil

import android.content.Context
import android.content.SharedPreferences
import com.example.smartalbum.model.LocationData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 位置数据本地缓存管理器
 * 上传失败时缓存到本地，网络恢复后自动重传
 */
class LocationCacheManager(context: Context) {

    companion object {
        private const val PREF_NAME = "location_cache"
        private const val KEY_CACHED_LOCATIONS = "cached_locations"
        private const val MAX_CACHE_SIZE = 1000 // 最多缓存1000条（约7天*24小时*6次=1008条）
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 保存位置数据到缓存
     */
    fun saveLocation(data: LocationData) {
        val cachedList = getCachedLocations()
        if (cachedList.size >= MAX_CACHE_SIZE) {
            // 超过最大缓存数，删除最旧的一条
            cachedList.removeAt(0)
        }
        cachedList.add(data)
        saveCachedLocations(cachedList)
    }

    /**
     * 获取所有缓存的位置数据
     */
    fun getCachedLocations(): MutableList<LocationData> {
        val jsonStr = prefs.getString(KEY_CACHED_LOCATIONS, "[]")
        val list = mutableListOf<LocationData>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    LocationData(
                        deviceId = obj.optString("deviceId"),
                        deviceName = obj.optString("deviceName"),
                        deviceModel = obj.optString("deviceModel"),
                        latitude = obj.optDouble("latitude"),
                        longitude = obj.optDouble("longitude"),
                        address = obj.optString("address"),
                        isHighAccuracy = obj.optBoolean("isHighAccuracy"),
                        time = obj.optString("time")
                    )
                )
            }
        } catch (e: Exception) {
            LogUtil.e("LocationCacheManager", "异常", e)
        }
        return list
    }

    /**
     * 删除指定的缓存位置数据（上传成功后调用）
     */
    fun removeLocation(data: LocationData) {
        val cachedList = getCachedLocations()
        val iterator = cachedList.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.deviceId == data.deviceId && item.time == data.time) {
                iterator.remove()
                break
            }
        }
        saveCachedLocations(cachedList)
    }

    /**
     * 清空所有缓存
     */
    fun clearAll() {
        prefs.edit().remove(KEY_CACHED_LOCATIONS).apply()
    }

    /**
     * 获取缓存数量
     */
    fun getCacheSize(): Int {
        return getCachedLocations().size
    }

    private fun saveCachedLocations(list: List<LocationData>) {
        val jsonArray = JSONArray()
        for (data in list) {
            val obj = JSONObject().apply {
                put("deviceId", data.deviceId)
                put("deviceName", data.deviceName)
                put("deviceModel", data.deviceModel)
                put("latitude", data.latitude)
                put("longitude", data.longitude)
                put("address", data.address)
                put("isHighAccuracy", data.isHighAccuracy)
                put("time", data.time)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_CACHED_LOCATIONS, jsonArray.toString()).apply()
    }
}
