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
    /** Human-readable summary of the Avoid/Prefer filters used to generate this
     * route (e.g. "Avoid: Highways, Hazards. Prefer: School zones."), set only
     * for routes saved from the trip generator -- see
     * [com.instructor.lessonroutes.data.routegen.summarize]. Null for tap/
     * recorded routes, which have no filters, and for any generated route
     * saved with every filter left at NONE. */
    val generationFilters: String? = null,
)
