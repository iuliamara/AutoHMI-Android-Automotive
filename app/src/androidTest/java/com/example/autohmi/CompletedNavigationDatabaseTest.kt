package com.example.autohmi

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletedNavigationDatabaseTest {
    private lateinit var context: Context
    private lateinit var name: String
    private var database: AutoHmiDatabase? = null
    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "autohmi-completed-test-${UUID.randomUUID()}.db"
    }
    private fun open() = Room.databaseBuilder(context, AutoHmiDatabase::class.java, name)
        .addMigrations(AutoHmiDatabase.MIGRATION_1_2).build().also { database = it }
    @After fun cleanup() { database?.close(); context.deleteDatabase(name) }

    @Test fun versionOneMigrationPreservesSnapshotsAndSupportsTheNewTable() = runBlocking {
        // Build the actual exported v1 schema, including its original Room identity hash.
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.example.autohmi.AutoHmiDatabase/1.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(name); file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { legacy.execSQL(setup.getString(it)) }
            legacy.execSQL("INSERT INTO trip_snapshots(id, savedAtMillis, distanceKm, remainingEnergyKwh, drivingMode) VALUES(42, 1000, 12.5, 31.25, 'ECO')")
            legacy.version = 1
        }
        val migrated = open()
        // Opening through Room executes the migration and validates its complete expected schema.
        val old = withTimeout(5000) { migrated.tripDao().observeSnapshots().first().single() }
        assertEquals(TripSnapshot(42, 1000, 12.5, 31.25, "ECO"), old)
        assertTrue(migrated.completedNavigationTripDao().observeCompletedTrips().first().isEmpty())
        val repository = TripRepository(migrated.tripDao(), migrated.completedNavigationTripDao())
        repository.saveCompletedTrip(trip("migration", 2000))
        val nextSnapshot = repository.saveSnapshot(VehicleStatus(distanceKm = 2.0))
        assertTrue(nextSnapshot > 42)
        assertEquals(old, migrated.tripDao().observeSnapshots().first().last())
        assertEquals(1, migrated.completedNavigationTripDao().observeCompletedTrips().first().size)
    }
    @Test fun duplicateInsertsAndRetriesKeepOneOriginalCompletionAndOrderNewestFirst() = runBlocking {
        val db = open(); val dao = db.completedNavigationTripDao()
        val original = trip("same-session", 2000)
        coroutineScope {
            val writes = List(3) { async { dao.insertCompletedTrip(original) } }
            assertEquals(1, writes.map { it.await() }.count { it != -1L })
        }
        dao.insertCompletedTrip(original.copy(completedAtMillis = 9000))
        dao.insertCompletedTrip(trip("newer", 3000)); dao.insertCompletedTrip(trip("older", 1000))
        val rows = dao.observeCompletedTrips().first()
        assertEquals(listOf("newer", "same-session", "older"), rows.map { it.completionId })
        assertEquals(original, rows[1])
        assertEquals(0, db.tripDao().countSnapshots())
    }
    @Test fun completedTripsAndSnapshotsSurviveReopeningAndRemainIndependent() = runBlocking {
        val db = open(); val repository = TripRepository(db.tripDao(), db.completedNavigationTripDao()) { 1000 }
        val snapshotId = repository.saveSnapshot(VehicleStatus(distanceKm = 4.5))
        val completed = trip("restart", 2000)
        repository.saveCompletedTrip(completed)
        db.close()
        val reopened = open()
        assertEquals(completed, reopened.completedNavigationTripDao().observeCompletedTrips().first().single())
        val restoredRepository = TripRepository(reopened.tripDao(), reopened.completedNavigationTripDao())
        restoredRepository.deleteSnapshot(snapshotId)
        assertTrue(reopened.tripDao().observeSnapshots().first().isEmpty())
        assertEquals(completed, reopened.completedNavigationTripDao().observeCompletedTrips().first().single())
    }
    private fun trip(id: String, timestamp: Long) = CompletedNavigationTrip(id, timestamp, 18.0, 150.0, 30.0,
        destinationLabel = "Offline destination", destinationGapMeters = 80.0)
}
