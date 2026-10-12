package com.example.smartalbum.util

import android.util.Base64
import java.nio.ByteBuffer

/**
 * 位置数据轻量级加密工具
 * 对经纬度进行XOR加密+Base64编码，防止数据库文件被直接读取时暴露精确位置
 * 注意：这不是军事级加密，仅用于防止普通用户直接查看
 */
object LocationEncryptor {

    // 加密密钥（硬编码，仅用于混淆）
    private val KEY = byteArrayOf(0x5A, 0x3C, 0x7F, 0x1B, 0x4E, 0x6D, 0x2A, 0x58)

    /**
     * 加密经纬度
     */
    fun encrypt(latitude: Double, longitude: Double): String {
        val latBytes = ByteBuffer.allocate(8).putDouble(latitude).array()
        val lngBytes = ByteBuffer.allocate(8).putDouble(longitude).array()
        val combined = latBytes + lngBytes
        // XOR加密
        for (i in combined.indices) {
            combined[i] = (combined[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密经纬度，返回Pair(latitude, longitude)
     */
    fun decrypt(encrypted: String): Pair<Double, Double> {
        val combined = Base64.decode(encrypted, Base64.NO_WRAP)
        // XOR解密
        for (i in combined.indices) {
            combined[i] = (combined[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        val latBytes = combined.copyOfRange(0, 8)
        val lngBytes = combined.copyOfRange(8, 16)
        val latitude = ByteBuffer.wrap(latBytes).double
        val longitude = ByteBuffer.wrap(lngBytes).double
        return Pair(latitude, longitude)
    }

    /**
     * 加密单个坐标值
     */
    fun encryptValue(value: Double): String {
        val bytes = ByteBuffer.allocate(8).putDouble(value).array()
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * 解密单个坐标值
     */
    fun decryptValue(encrypted: String): Double {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return ByteBuffer.wrap(bytes).double
    }
}
