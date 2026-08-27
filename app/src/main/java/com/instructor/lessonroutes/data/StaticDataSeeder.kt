package com.instructor.lessonroutes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * One-time seed of the static reference data (school zones, speed cameras,
 * high-volume roads) from bundled asset snapshots into Room, if the tables
 * are still empty. See
 * app/src/main/assets/{school_zones,speed_cameras,high_volume_roads}.json
 * and README.md for where these came from and how to refresh them --
 * school_zones/speed_cameras are a manual export, not fetched from a
 * live/confirmed download endpoint; high_volume_roads *is* fetchable live
 * (TrafficVolumeApi.fetchHighVolumeRoads(), kept around for exactly this
 * refresh purpose) but deliberately isn't called at app runtime any more --
 * see HighVolumeRoadEntity's own doc comment for why.
 */
suspend fun seedStaticDataIfNeeded(context: Context, database: AppDatabase) = withContext(Dispatchers.IO) {
    val schoolZoneDao = database.schoolZoneDao()
    if (schoolZoneDao.count() == 0) {
        val json = context.assets.open("school_zones.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val zones = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            SchoolZone(
                id = obj.getLong("id"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                speedLimitKmh = obj.getInt("speedLimitKmh"),
            )
        }
        schoolZoneDao.insertAll(zones)
    }

    val speedCameraDao = database.speedCameraDao()
    if (speedCameraDao.count() == 0) {
        val json = context.assets.open("speed_cameras.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val cameras = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            SpeedCamera(
                id = obj.getLong("id"),
                type = obj.getString("type"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                location = obj.optString("location").takeIf { it.isNotBlank() },
                isSchoolZone = obj.getBoolean("isSchoolZone"),
            )
        }
        speedCameraDao.insertAll(cameras)
    }

    val highVolumeRoadDao = database.highVolumeRoadDao()
    if (highVolumeRoadDao.count() == 0) {
        val json = context.assets.open("high_volume_roads.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val roads = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            HighVolumeRoadEntity(
                stationKey = obj.getLong("id"),
                roadName = obj.getString("roadName"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                year = obj.getInt("year"),
                trafficCount = obj.getInt("trafficCount"),
            )
        }
        highVolumeRoadDao.insertAll(roads)
    }
}
