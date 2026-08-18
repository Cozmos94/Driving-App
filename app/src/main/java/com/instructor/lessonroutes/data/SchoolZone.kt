package com.instructor.lessonroutes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Static reference data (spec step 10), seeded once from the bundled
 * `assets/school_zones.json` snapshot -- filtered down from the full NSW "Speed
 * Zones" open dataset (447k records covering every speed-zoned road segment) to
 * just the ~3,700 records tagged as school zones, reprojected from the source
 * shapefile's Web Mercator coordinates to plain lat/lon.
 */
@Entity(tableName = "school_zones")
data class SchoolZone(
    @PrimaryKey val id: Long,
    val latitude: Double,
    val longitude: Double,
    val speedLimitKmh: Int,
)
