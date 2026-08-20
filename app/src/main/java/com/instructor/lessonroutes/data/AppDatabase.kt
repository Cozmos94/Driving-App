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
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao
    abstract fun schoolZoneDao(): SchoolZoneDao
    abstract fun speedCameraDao(): SpeedCameraDao
    abstract fun studentProfileDao(): StudentProfileDao

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

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lessonroutes.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
