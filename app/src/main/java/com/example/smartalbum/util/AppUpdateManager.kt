package com.example.smartalbum.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.example.smartalbum.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * APP版本更新管理器
 * 检查更新、下载APK、安装APK
 *
 * 安全说明：
 * 1. 所有请求走 HTTPS（BuildConfig.UPDATE_BASE_URL）。开源版不内置更新服务器，
 *    如需启用自动更新，请在 app/build.gradle 配置你自己的更新服务器地址。
 * 2. 下载的 APK 在安装前会校验其签名证书是否与当前已安装应用一致，
 *    防止中间人替换 APK 导致的远程代码执行（RCE）。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdate"
    // 版本信息文件地址（HTTPS）
    private const val VERSION_URL = BuildConfig.UPDATE_BASE_URL + "/apks/smartalbum_version.json"
    // APK下载地址前缀（HTTPS）
    private const val APK_BASE_URL = BuildConfig.UPDATE_BASE_URL + "/apks/"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val updateLog: String,
        val forceUpdate: Boolean
    )

    /**
     * 检查更新
     */
    fun checkUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val url = URL(VERSION_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val json = JSONObject(response)
                val updateInfo = UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    downloadUrl = json.optString("downloadUrl", APK_BASE_URL + "SmartAlbum_v${json.getString("versionName")}.apk"),
                    updateLog = json.optString("updateLog", ""),
                    forceUpdate = json.optBoolean("forceUpdate", false)
                )

                // 获取当前版本
                val currentVersionCode = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                } catch (e: Exception) {
                    0
                }

                if (updateInfo.versionCode > currentVersionCode) {
                    onResult(updateInfo)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "检查更新失败", e)
                onResult(null)
            }
        }.start()
    }

    /**
     * 显示更新对话框
     */
    fun showUpdateDialog(context: Context, updateInfo: UpdateInfo) {
        val message = buildString {
            append("发现新版本：v${updateInfo.versionName}\n\n")
            if (updateInfo.updateLog.isNotEmpty()) {
                append("更新内容：\n${updateInfo.updateLog}\n\n")
            }
            append("是否立即更新？")
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("版本更新")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(context, updateInfo)
            }

        if (!updateInfo.forceUpdate) {
            builder.setNegativeButton("稍后再说", null)
        }

        builder.setCancelable(!updateInfo.forceUpdate)
        builder.show()
    }

    /**
     * 下载并安装APK
     */
    private fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(updateInfo.downloadUrl)

            val request = DownloadManager.Request(uri)
            request.setTitle("智能相册更新")
            request.setDescription("正在下载 v${updateInfo.versionName}")
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "SmartAlbum_v${updateInfo.versionName}.apk"
            )
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setMimeType("application/vnd.android.package-archive")

            val downloadId = downloadManager.enqueue(request)
            LogUtil.i(TAG, "开始下载APK，ID: $downloadId")

            // 提示用户
            android.os.Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, "开始下载更新包...", android.widget.Toast.LENGTH_SHORT).show()
            }

            // 监听下载完成
            Thread {
                try {
                    var downloading = true
                    // 2026-10-13：加轮询兜底。原 while 循环只在 SUCCESSFUL/FAILED 退出，
                    // DownloadManager 异常卡住（既不死也不成功）时这个线程会永远轮询。
                    // 最多轮询 30 分钟，超时提示用户手动检查下载。
                    val maxPollCount = 30 * 60
                    var pollCount = 0
                    while (downloading && pollCount < maxPollCount) {
                        pollCount++
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor.moveToFirst()) {
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                downloading = false
                                // 下载完成，先验签再安装
                                val apkFile = File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                    "SmartAlbum_v${updateInfo.versionName}.apk"
                                )
                                if (verifyApkSignature(context, apkFile)) {
                                    installApk(context, apkFile)
                                } else {
                                    apkFile.delete()
                                    android.os.Handler(context.mainLooper).post {
                                        android.widget.Toast.makeText(
                                            context,
                                            "更新包签名校验失败，已取消安装以保证安全",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                downloading = false
                                android.os.Handler(context.mainLooper).post {
                                    android.widget.Toast.makeText(context, "下载失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        cursor.close()
                        if (downloading) {
                            Thread.sleep(1000)
                        }
                    }
                    if (downloading) {
                        // 轮询超时：DownloadManager 未回报完成/失败
                        LogUtil.w(TAG, "下载轮询超时（${maxPollCount}s），下载ID: $downloadId")
                        android.os.Handler(context.mainLooper).post {
                            android.widget.Toast.makeText(
                                context,
                                "下载等待超时，请到系统下载管理中查看进度",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e(TAG, "监听下载失败", e)
                }
            }.start()

        } catch (e: Exception) {
            LogUtil.e(TAG, "下载APK失败", e)
            android.widget.Toast.makeText(context, "下载失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 安装APK
     */
    private fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                LogUtil.e(TAG, "APK文件不存在: ${apkFile.absolutePath}")
                return
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            android.os.Handler(context.mainLooper).post {
                context.startActivity(intent)
            }

            LogUtil.i(TAG, "开始安装APK: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            LogUtil.e(TAG, "安装APK失败", e)
            android.os.Handler(context.mainLooper).post {
                android.widget.Toast.makeText(context, "安装失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 校验下载的 APK 是否与当前已安装应用使用同一签名证书。
     *
     * 自更新要求新 APK 与已安装应用同签名（否则系统安装会报
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE）。若下载链路被中间人替换成恶意 APK，
     * 其签名必然与当前应用不一致，此处提前拦截，避免用户看到诡异安装错误或被植入恶意代码。
     */
    @Suppress("DEPRECATION")
    private fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        return try {
            val current = currentAppSignatureSha256(context)
            val downloaded = apkSignatureSha256(context, apkFile)
            when {
                current == null || downloaded == null -> {
                    LogUtil.w(TAG, "无法获取签名信息，保守拒绝安装")
                    false
                }
                current.equals(downloaded, ignoreCase = true) -> true
                else -> {
                    LogUtil.w(TAG, "APK签名不匹配：当前=$current 下载=$downloaded")
                    false
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "校验APK签名异常", e)
            false
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun currentAppSignatureSha256(context: Context): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pi = pm.getPackageInfo(context.packageName, flags) ?: return null
        val cert = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.signingInfo?.apkContentsSigners?.firstOrNull()
        else pi.signatures?.firstOrNull())
            ?: return null
        return sha256Hex(cert.toByteArray())
    }

    @Suppress("DEPRECATION")
    private fun apkSignatureSha256(context: Context, apkFile: File): String? {
        val pi = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        val cert = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.signingInfo?.apkContentsSigners?.firstOrNull()
        else pi.signatures?.firstOrNull())
            ?: return null
        return sha256Hex(cert.toByteArray())
    }
}
