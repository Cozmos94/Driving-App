package com.instructor.lessonroutes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Route::class,
        RoutePoint::class,
        SchoolZone::class,
        SpeedCamera::class,
        StudentProfile::class,
        RouteStudentProfileCrossRef::class,
        HighVolumeRoadEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun schoolZoneDao(): SchoolZoneDao
    abstract fun speedCameraDao(): SpeedCameraDao
    abstract fun studentProfileDao(): StudentProfileDao
    abstract fun highVolumeRoadDao(): HighVolumeRoadDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 -> v3: adds student_profiles + the route<->profile join table. Purely
         * additive (no existing table's columns changed), so a hand-written Migration is
         * low-risk here -- the SQL below must still match Room's expected schema exactly,
         * see [MIGRATION_2_3]. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `student_profiles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `dateCreated` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `route_student_profile` (
                        `routeId` INTEGER NOT NULL,
                        `studentProfileId` INTEGER NOT NULL,
                        PRIMARY KEY(`routeId`, `studentProfileId`),
                        FOREIGN KEY(`routeId`) REFERENCES `routes`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`studentProfileId`) REFERENCES `student_profiles`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_student_profile_routeId` " +
                        "ON `route_student_profile` (`routeId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_route_student_profile_studentProfileId` " +
                        "ON `route_student_profile` (`studentProfileId`)",
                )
            }
        }

        /** v3 -> v4: adds `routes.generationFilters`, a nullable summary of the
         * Avoid/Prefer filters used to generate a route (see [Route]'s doc
         * comment) -- purely additive, same low-risk shape as [MIGRATION_2_3]. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `routes` ADD COLUMN `generationFilters` TEXT")
            }
        }

        /** v4 -> v5: adds `routes.avoidFilters`/`routes.preferFilters`, replacing
         * the single-paragraph `generationFilters` (still present, just unused
         * going forward -- see [Route]'s doc comment) with two comma-joined
         * lists for a structured display. Purely additive. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `routes` ADD COLUMN `avoidFilters` TEXT")
                db.execSQL("ALTER TABLE `routes` ADD COLUMN `preferFilters` TEXT")
            }
        }

        /** v5 -> v6: adds `high_volume_roads`, seeded from a bundled snapshot
         * instead of a live TfNSW call on every use (see
         * HighVolumeRoadEntity's own doc comment for why) -- purely
         * additive, same low-risk shape as the earlier migrations here. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `high_volume_roads` (
                        `stationKey` INTEGER PRIMARY KEY NOT NULL,
                        `roadName` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `year` INTEGER NOT NULL,
                        `trafficCount` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lessonroutes.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
    }
}
