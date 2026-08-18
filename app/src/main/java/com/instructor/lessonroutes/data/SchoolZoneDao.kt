package com.instructor.lessonroutes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SchoolZoneDao {
    @Query("SELECT COUNT(*) FROM school_zones")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(zones: List<SchoolZone>)

    @Query("SELECT * FROM school_zones")
    suspend fun getAll(): List<SchoolZone>
}
