package com.example.smartalbum.util

/**
 * 坐标转换工具类
 * 支持WGS84(GPS) -> GCJ02(火星坐标/高德)转换
 */
object CoordinateConverter {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * WGS84坐标转换为GCJ02坐标
     * @param wgsLat WGS84纬度
     * @param wgsLon WGS84经度
     * @return GCJ02坐标对 (纬度, 经度)
     */
    fun convertToGcj02(wgsLat: Double, wgsLon: Double): Pair<Double, Double> {
        if (isOutOfChina(wgsLat, wgsLon)) {
            return Pair(wgsLat, wgsLon)
        }
        var dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0)
        var dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        val mgLat = wgsLat + dLat
        val mgLon = wgsLon + dLon
        return Pair(mgLat, mgLon)
    }

    /**
     * WGS84坐标转换为GCJ02坐标（别名，兼容旧代码）
     * @param lat WGS84纬度
     * @param lon WGS84经度
     * @return GCJ02坐标对 (纬度, 经度)
     */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        return convertToGcj02(lat, lon)
    }


    /**
     * GCJ02坐标转换为WGS84坐标（迭代法，精度<1e-6）
     * @param gcjLat GCJ02纬度
     * @param gcjLon GCJ02经度
     * @return WGS84坐标对 (纬度, 经度)
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        if (isOutOfChina(gcjLat, gcjLon)) {
            return Pair(gcjLat, gcjLon)
        }
        var wgsLat = gcjLat
        var wgsLon = gcjLon
        // 迭代3次收敛
        for (i in 0 until 3) {
            val (mgLat, mgLon) = convertToGcj02(wgsLat, wgsLon)
            wgsLat = gcjLat - (mgLat - wgsLat)
            wgsLon = gcjLon - (mgLon - wgsLon)
        }
        return Pair(wgsLat, wgsLon)
    }

    /**
     * WGS84坐标对转换为GCJ02坐标对
     * @param gps WGS84坐标对 (纬度, 经度)，为null时返回null
     * @return GCJ02坐标对 (纬度, 经度)，为null时返回null
     */
    fun convertToGcj02(gps: Pair<Double, Double>?): Pair<Double, Double>? {
        if (gps == null) return null
        val (lat, lon) = gps
        if (lat == 0.0 && lon == 0.0) return null
        return convertToGcj02(lat, lon)
    }

    private fun isOutOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}

