package com.example.smartalbum.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.IgnoredOnParcel

/**
 * 位置分组数据类
 * 同一位置（经纬度相近）的照片归为一组
 */
@Parcelize
data class LocationGroup(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val locationName: String,
    val photos: List<Photo>
) : Parcelable {
    @IgnoredOnParcel
    val coverPhoto: Photo? = photos.firstOrNull()

    @IgnoredOnParcel
    val photoCount: Int
        get() = photos.size

    @IgnoredOnParcel
    val earliestDate: Long?
        get() = photos.minOfOrNull { it.dateTaken }

    @IgnoredOnParcel
    val latestDate: Long?
        get() = photos.maxOfOrNull { it.dateTaken }
}
