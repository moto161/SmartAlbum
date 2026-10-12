package com.example.smartalbum

import android.app.Application
import com.amap.api.location.AMapLocationClient
import dagger.hilt.android.HiltAndroidApp

/**
 * 智能相册APP Application类
 * 使用Hilt进行依赖注入
 */
@HiltAndroidApp
class SmartAlbumApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化主题管理器
        com.example.smartalbum.util.ThemeManager.init(this)

        // 高德地图SDK隐私合规接口（必须在调用任何SDK接口前调用）
        try {
            // 定位SDK隐私合规
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
            android.util.Log.i("SmartAlbumApp", "定位SDK隐私合规接口已调用")

            // 地图SDK隐私合规（2D版本）
            try {
                val mapsInitializerClass = Class.forName("com.amap.api.maps2d.MapsInitializer")
                val updatePrivacyShowMethod = mapsInitializerClass.getMethod(
                    "updatePrivacyShow",
                    android.content.Context::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                updatePrivacyShowMethod.invoke(null, this, true, true)

                val updatePrivacyAgreeMethod = mapsInitializerClass.getMethod(
                    "updatePrivacyAgree",
                    android.content.Context::class.java,
                    Boolean::class.javaPrimitiveType
                )
                updatePrivacyAgreeMethod.invoke(null, this, true)
                android.util.Log.i("SmartAlbumApp", "地图SDK隐私合规接口已调用")
            } catch (e: ClassNotFoundException) {
                android.util.Log.e("SmartAlbumApp", "地图SDK类未找到: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("SmartAlbumApp", "地图SDK隐私合规接口调用失败", e)
            }

            // 搜索SDK隐私合规
            try {
                val serviceSettingsClass = Class.forName("com.amap.api.services.core.ServiceSettings")
                val updatePrivacyShowMethod = serviceSettingsClass.getMethod(
                    "updatePrivacyShow",
                    android.content.Context::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                updatePrivacyShowMethod.invoke(null, this, true, true)

                val updatePrivacyAgreeMethod = serviceSettingsClass.getMethod(
                    "updatePrivacyAgree",
                    android.content.Context::class.java,
                    Boolean::class.javaPrimitiveType
                )
                updatePrivacyAgreeMethod.invoke(null, this, true)
                android.util.Log.i("SmartAlbumApp", "搜索SDK隐私合规接口已调用")
            } catch (e: ClassNotFoundException) {
                android.util.Log.e("SmartAlbumApp", "搜索SDK类未找到: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("SmartAlbumApp", "搜索SDK隐私合规接口调用失败", e)
            }

        } catch (e: Exception) {
            android.util.Log.e("SmartAlbumApp", "高德地图隐私合规接口调用失败", e)
        }
    }
}
