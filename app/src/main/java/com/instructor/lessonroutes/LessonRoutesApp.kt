package com.instructor.lessonroutes

import android.app.Application
import org.maplibre.android.MapLibre

class LessonRoutesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run once before any MapView is created. No API key/account needed —
        // this only wires up MapLibre's internal file/http caches.
        MapLibre.getInstance(this)
    }
}
