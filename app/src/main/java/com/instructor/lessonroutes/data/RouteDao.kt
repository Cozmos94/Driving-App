package com.instructor.lessonroutes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY dateCreated DESC")
    fun getAllRoutes(): Flow<List<Route>>

    @Transaction
    @Query("SELECT * FROM routes WHERE id = :routeId")
    fun getRouteWithPoints(routeId: Long): Flow<RouteWithPoints?>

    @Insert
    suspend fun insertRoute(route: Route): Long

    @Update
    suspend fun updateRoute(route: Route)

    @Delete
    suspend fun deleteRoute(route: Route)

    @Insert
    suspend fun insertPoints(points: List<RoutePoint>)

    /** RoutePoint rows cascade-delete via their FK, per the entity's onDelete. */
    @Query("DELETE FROM routes")
    suspend fun deleteAllRoutes()
}
