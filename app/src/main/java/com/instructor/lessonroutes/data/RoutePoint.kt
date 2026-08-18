package com.instructor.lessonroutes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "route_points",
    foreignKeys = [
        ForeignKey(
            entity = Route::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routeId")],
)
data class RoutePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val latitude: Double,
    val longitude: Double,
    /** Preserves point order along the route. */
    val sequenceOrder: Int,
    /** Epoch millis; set when recorded, null when tapped. */
    val timestamp: Long? = null,
    /** Marks a meaningful stop, e.g. "parallel park here". */
    val isWaypoint: Boolean = false,
)
