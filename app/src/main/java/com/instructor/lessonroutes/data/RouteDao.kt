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

    /** Every route plus the student profiles it's saved against, for the list screen's
     * profile filter and the edit dialog's profile picker. */
    @Transaction
    @Query("SELECT * FROM routes ORDER BY dateCreated DESC")
    fun getAllRoutesWithProfiles(): Flow<List<RouteWithProfiles>>

    @Transaction
    @Query("SELECT * FROM routes WHERE id = :routeId")
    fun getRouteWithPoints(routeId: Long): Flow<RouteWithPoints?>

    @Transaction
    @Query("SELECT * FROM routes WHERE id = :routeId")
    fun getRouteWithProfiles(routeId: Long): Flow<RouteWithProfiles?>

    @Insert
    suspend fun insertRoute(route: Route): Long

    @Update
    suspend fun updateRoute(route: Route)

    @Delete
    suspend fun deleteRoute(route: Route)

    @Insert
    suspend fun insertPoints(points: List<RoutePoint>)

    @Insert
    suspend fun insertRouteProfileCrossRefs(crossRefs: List<RouteStudentProfileCrossRef>)

    @Query("DELETE FROM route_student_profile WHERE routeId = :routeId")
    suspend fun clearProfilesForRoute(routeId: Long)

    /** Replaces a route's profile assignments in one go: clear then re-insert. */
    @Transaction
    suspend fun setProfilesForRoute(routeId: Long, profileIds: List<Long>) {
        clearProfilesForRoute(routeId)
        if (profileIds.isNotEmpty()) {
            insertRouteProfileCrossRefs(profileIds.map { RouteStudentProfileCrossRef(routeId, it) })
        }
    }

    /** RoutePoint and route_student_profile rows cascade-delete via their FK, per each
     * entity's onDelete. */
    @Query("DELETE FROM routes")
    suspend fun deleteAllRoutes()
}
