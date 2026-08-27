package com.instructor.lessonroutes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HighVolumeRoadDao {
    @Query("SELECT COUNT(*) FROM high_volume_roads")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(roads: List<HighVolumeRoadEntity>)

    @Query("SELECT * FROM high_volume_roads")
    suspend fun getAll(): List<HighVolumeRoadEntity>
}
