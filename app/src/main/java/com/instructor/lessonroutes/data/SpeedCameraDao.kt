package com.instructor.lessonroutes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SpeedCameraDao {
    @Query("SELECT COUNT(*) FROM speed_cameras")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(cameras: List<SpeedCamera>)

    @Query("SELECT * FROM speed_cameras")
    suspend fun getAll(): List<SpeedCamera>
}
