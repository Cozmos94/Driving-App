package com.instructor.lessonroutes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class Route(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    /** Free text, e.g. "good for roundabout practice, avoid 3pm school zone". */
    val notes: String? = null,
    /** Epoch millis. */
    val dateCreated: Long,
    /** Optional, e.g. student level or skill focus. */
    val tag: String? = null,
)
