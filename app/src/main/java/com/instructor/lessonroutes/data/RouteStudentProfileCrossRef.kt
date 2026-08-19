package com.instructor.lessonroutes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Join table for the many-to-many between [Route] and [StudentProfile]: a route can be
 * saved against zero, one, or several student profiles, and a profile can have many
 * routes.
 */
@Entity(
    tableName = "route_student_profile",
    primaryKeys = ["routeId", "studentProfileId"],
    foreignKeys = [
        ForeignKey(
            entity = Route::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StudentProfile::class,
            parentColumns = ["id"],
            childColumns = ["studentProfileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("routeId"), Index("studentProfileId")],
)
data class RouteStudentProfileCrossRef(
    val routeId: Long,
    val studentProfileId: Long,
)
