package com.instructor.lessonroutes.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class RouteWithProfiles(
    @Embedded val route: Route,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = RouteStudentProfileCrossRef::class,
            parentColumn = "routeId",
            entityColumn = "studentProfileId",
        ),
    )
    val profiles: List<StudentProfile>,
)
