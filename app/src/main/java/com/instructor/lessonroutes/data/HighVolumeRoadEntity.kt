package com.instructor.lessonroutes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Static reference data ("High traffic roads" filter/overlay), seeded once
 * from the bundled `assets/high_volume_roads.json` snapshot -- was a live
 * TfNSW Traffic Volume Counts API call on every use (see
 * TrafficVolumeApi.kt's own history), switched to a bundled snapshot
 * because the underlying data is TfNSW's own *yearly summary* table --
 * confirmed via the real query (see TrafficVolumeApi.kt) it groups by
 * `year`, meaning this changes at most once a year when a new year's
 * aggregate is published, not truly live/real-time. That made it a real
 * live-dependency risk (a genuine reported failure: "couldn't load data
 * for... Highways" tracing back to this exact call) for data that barely
 * changes -- removing the live call removes that risk entirely rather than
 * needing a fallback provider for it.
 *
 * `TrafficVolumeApi.fetchHighVolumeRoads()` is kept as-is (still a real,
 * working live call) purely as the tool for periodically regenerating this
 * snapshot -- see README.md for the refresh steps. It's no longer called at
 * app runtime.
 *
 * Deliberately doesn't carry the runtime-only `geometry` field that
 * TrafficVolumeApi.kt's own `HighVolumeRoad` domain type has -- that's
 * looked up live via Overpass road-matching each time the live map draws
 * this overlay (LiveMapScreen.kt), not something to freeze into this
 * snapshot.
 */
@Entity(tableName = "high_volume_roads")
data class HighVolumeRoadEntity(
    @PrimaryKey val stationKey: Long,
    val roadName: String,
    val latitude: Double,
    val longitude: Double,
    val year: Int,
    val trafficCount: Int,
)
