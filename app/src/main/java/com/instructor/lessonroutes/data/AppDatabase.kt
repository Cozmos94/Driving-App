package com.instructor.lessonroutes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Route::class, RoutePoint::class, SchoolZone::class, SpeedCamera::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun schoolZoneDao(): SchoolZoneDao
    abstract fun speedCameraDao(): SpeedCameraDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lessonroutes.db",
                )
                    // Dev-stage tradeoff: this is a straight schema bump (two new tables,
                    // nothing about Route/RoutePoint changed) but a hand-written Migration
                    // has to match Room's expected SQL exactly or it crashes on upgrade --
                    // a common, hard-to-diagnose failure mode. Destructive fallback is
                    // simpler and safer for now; it means anyone with the app already
                    // installed loses their saved routes on this update. Worth writing a
                    // real Migration before this app has real users' data to protect.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
