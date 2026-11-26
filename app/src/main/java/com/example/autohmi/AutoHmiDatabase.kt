
package com.example.autohmi

import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripSnapshot::class, CompletedNavigationTrip::class],
    version = 2,
    exportSchema = true
)
abstract class AutoHmiDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun completedNavigationTripDao(): CompletedNavigationTripDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `completed_navigation_trips` (`completionId` TEXT NOT NULL, `completedAtMillis` INTEGER NOT NULL, `simulatedDurationSeconds` REAL NOT NULL, `distanceMeters` REAL NOT NULL, `averageSpeedKmh` REAL NOT NULL, `source` TEXT NOT NULL, `completionStatus` TEXT NOT NULL, `destinationLabel` TEXT, `destinationGapMeters` REAL NOT NULL, PRIMARY KEY(`completionId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_completed_navigation_trips_completedAtMillis` ON `completed_navigation_trips` (`completedAtMillis`)")
            }
        }

        @Volatile
        private var INSTANCE: AutoHmiDatabase? = null

        fun getInstance(context: Context): AutoHmiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoHmiDatabase::class.java,
                    "autohmi_database"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
