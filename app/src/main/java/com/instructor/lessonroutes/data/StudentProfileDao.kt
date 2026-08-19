package com.instructor.lessonroutes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentProfileDao {
    @Query("SELECT * FROM student_profiles ORDER BY name COLLATE NOCASE")
    fun getAllProfiles(): Flow<List<StudentProfile>>

    @Insert
    suspend fun insertProfile(profile: StudentProfile): Long

    @Update
    suspend fun updateProfile(profile: StudentProfile)

    @Delete
    suspend fun deleteProfile(profile: StudentProfile)
}
