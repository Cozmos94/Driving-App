package com.instructor.lessonroutes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * One-time seed of the static reference data (school zones, speed cameras) from
 * bundled asset snapshots into Room, if the tables are still empty. See
 * app/src/main/assets/{school_zones,speed_cameras}.json and README.md for where
 * these came from and how to refresh them -- they're a manual export, not fetched
 * from a live/confirmed download endpoint.
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
}
