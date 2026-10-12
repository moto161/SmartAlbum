package com.example.smartalbum.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 照片数据类
 */
@Parcelize
data class Photo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationName: String? = null
) : Parcelable {
    val hasLocation: Boolean
        get() = latitude != null && longitude != null &&
                latitude != 0.0 && longitude != 0.0
}
