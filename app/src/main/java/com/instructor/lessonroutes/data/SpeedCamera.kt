package com.instructor.lessonroutes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object SpeedCameraType {
    const val FIXED = "FIXED"
    const val RED_LIGHT = "RED_LIGHT"
}

/**
 * Static reference data (spec step 10-adjacent), seeded once from the bundled
 * `assets/speed_cameras.json` snapshot (fixed + red-light camera locations).
 * Plain String [type] rather than a Kotlin enum to avoid relying on Room's enum
 * TypeConverter support -- keeps this entity simple and dependency-free.
 *
 * Mobile speed camera zones are NOT included here: the only source data available
 * for those lists suburb/street names with no coordinates, so there's nothing to
 * plot without a geocoding step (out of scope -- would need a geocoding API).
 */
@Entity(tableName = "speed_cameras")
data class SpeedCamera(
    @PrimaryKey val id: Long,
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val location: String?,
    val isSchoolZone: Boolean,
)
