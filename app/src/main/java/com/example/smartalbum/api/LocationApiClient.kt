package com.example.smartalbum.api

import android.util.Log
import com.example.smartalbum.BuildConfig
import com.example.smartalbum.model.LocationData
import com.example.smartalbum.model.TrailPoint
import com.example.smartalbum.model.UserInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 位置追踪API客户端
 *
 * 所有请求走 HTTPS（BuildConfig.API_BASE_URL）。开源版不内置后端地址，
 * 如需启用位置上报，请在 app/build.gradle 中配置你自己的 API 地址。
 */
object LocationApiClient {
    private const val TAG = "LocationApi"
    private const val BASE_URL = BuildConfig.API_BASE_URL + "/api"
    private const val TIMEOUT = 10000

    /**
     * 上传位置信息
     */
    fun uploadLocation(data: LocationData): Boolean {
        return try {
            val json = JSONObject().apply {
                put("device_id", data.deviceId)
                put("device_name", data.deviceName)
                put("device_model", data.deviceModel)
                put("latitude", data.latitude)
                put("longitude", data.longitude)
                put("address", data.address)
                put("is_high_accuracy", data.isHighAccuracy)
            }
            val result = postRequest("$BASE_URL/location/upload", json.toString())
            val jsonResult = JSONObject(result)
            jsonResult.optBoolean("success", false)
        } catch (e: Exception) {
            Log.e(TAG, "上传位置失败: ${e.message}")
            false
        }
    }

    /**
     * 获取所有用户列表
     */
    fun getUserList(): List<UserInfo> {
        return try {
            val result = getRequest("$BASE_URL/location/list")
            val jsonResult = JSONObject(result)
            if (jsonResult.optBoolean("success", false)) {
                val dataArray = jsonResult.getJSONArray("data")
                val list = mutableListOf<UserInfo>()
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    list.add(
                        UserInfo(
                            deviceId = obj.optString("device_id"),
                            deviceName = obj.optString("device_name"),
                            deviceModel = obj.optString("device_model"),
                            latitude = obj.optDouble("latitude"),
                            longitude = obj.optDouble("longitude"),
                            address = obj.optString("address"),
                            remark = obj.optString("remark"),
                            lastUpdate = obj.optString("last_update"),
                            trailCount = obj.optInt("trail_count")
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取用户列表失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取用户历史轨迹
     */
    fun getUserTrails(deviceId: String, limit: Int = 100): List<TrailPoint> {
        return try {
            val result = getRequest("$BASE_URL/location/trails/$deviceId?limit=$limit")
            val jsonResult = JSONObject(result)
            if (jsonResult.optBoolean("success", false)) {
                val dataArray = jsonResult.getJSONArray("data")
                val list = mutableListOf<TrailPoint>()
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    list.add(
                        TrailPoint(
                            latitude = obj.optDouble("latitude"),
                            longitude = obj.optDouble("longitude"),
                            address = obj.optString("address"),
                            isHighAccuracy = obj.optInt("is_high_accuracy") == 1,
                            createdAt = obj.optString("created_at")
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取历史轨迹失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 更新用户备注
     */
    fun updateRemark(deviceId: String, remark: String): Boolean {
        return try {
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("remark", remark)
            }
            val result = postRequest("$BASE_URL/location/remark", json.toString())
            val jsonResult = JSONObject(result)
            jsonResult.optBoolean("success", false)
        } catch (e: Exception) {
            Log.e(TAG, "更新备注失败: ${e.message}")
            false
        }
    }

    /**
     * 删除用户/设备
     */
    fun deleteUser(deviceId: String): Boolean {
        return try {
            val json = JSONObject().apply {
                put("device_id", deviceId)
            }
            val result = postRequest("$BASE_URL/location/delete", json.toString())
            val jsonResult = JSONObject(result)
            jsonResult.optBoolean("success", false)
        } catch (e: Exception) {
            Log.e(TAG, "删除用户失败: ${e.message}")
            false
        }
    }

    private fun postRequest(urlStr: String, jsonBody: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Auth-Token", BuildConfig.API_TOKEN)
        conn.doOutput = true

        val os: OutputStream = conn.outputStream
        os.write(jsonBody.toByteArray())
        os.flush()
        os.close()

        val responseCode = conn.responseCode
        val reader = BufferedReader(InputStreamReader(if (responseCode == 200) conn.inputStream else conn.errorStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        conn.disconnect()
        return response.toString()
    }

    private fun getRequest(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT
        conn.readTimeout = TIMEOUT
        conn.setRequestProperty("X-Auth-Token", BuildConfig.API_TOKEN)

        val responseCode = conn.responseCode
        val reader = BufferedReader(InputStreamReader(if (responseCode == 200) conn.inputStream else conn.errorStream))
        val response = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()
        conn.disconnect()
        return response.toString()
    }
}
