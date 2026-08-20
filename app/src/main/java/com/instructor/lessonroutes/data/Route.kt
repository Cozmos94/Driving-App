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
    /** @deprecated Superseded by [avoidFilters]/[preferFilters] (added one
     * schema version later) for a structured display instead of one paragraph.
     * Left in place, unused, rather than dropped -- SQLite's ALTER TABLE DROP
     * COLUMN support depends on the device's bundled SQLite version, so a
     * recreate-and-copy migration would be needed to remove it safely; not
     * worth that risk for one dead nullable column. */
    val generationFilters: String? = null,
    /** Comma-separated filter category display names set to Avoid when this
     * route was generated (e.g. "Highways, Hazards"), in [
     * com.instructor.lessonroutes.data.routegen.ALL_FILTER_LABELS]'s fixed
     * order -- see [com.instructor.lessonroutes.data.routegen.FilterSummary].
     * Null for tap/recorded routes (no filters) and for a generated route with
     * nothing set to Avoid. */
    val avoidFilters: String? = null,
    /** Same as [avoidFilters] but for Prefer. */
    val preferFilters: String? = null,
)
