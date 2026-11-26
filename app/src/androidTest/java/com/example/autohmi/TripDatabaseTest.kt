package com.example.autohmi

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AutoHmiDatabase
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "autohmi-test-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    private fun openDatabase() = Room.databaseBuilder(context, AutoHmiDatabase::class.java, databaseName).build()

    @After
    fun tearDown() {
        database.close()
        // This name is unique to this test; the application's database is never touched.
        context.deleteDatabase(databaseName)
    }

    @Test
    fun insertRetrieveAndOrderSnapshotsNewestFirstWithIdTieBreak() = runBlocking {
        val dao = database.tripDao()
        val first = dao.insert(snapshot(2_000, 1.0))
        val older = dao.insert(snapshot(1_000, 2.0))
        val tied = dao.insert(snapshot(2_000, 3.0))
        val rows = withTimeout(5_000) { dao.observeSnapshots().first() }
        assertEquals(listOf(tied, first, older), rows.map { it.id })
        assertEquals(3.0, rows.first().distanceKm, 0.0)
        assertEquals("SPORT", rows.first().drivingMode)
        assertEquals(25.0, rows.first().remainingEnergyKwh, 0.0)
    }

    @Test
    fun recentQueryIsLimitedToFiveWhileFullHistoryIncludesAll() = runBlocking {
        val dao = database.tripDao()
        repeat(7) { dao.insert(snapshot(1_000L + it, it + 1.0)) }
        val recent = withTimeout(5_000) { dao.observeRecentSnapshots().first() }
        assertEquals(5, recent.size)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), recent.map { it.id })
        assertEquals(7, dao.observeSnapshots().first().size)
    }

    @Test
    fun snapshotsSurviveClosingAndReopeningTheDatabase() = runBlocking {
        val id = TripRepository(database.tripDao()) { 1_000L }
            .saveSnapshot(VehicleStatus(distanceKm = 4.5, drivingMode = DrivingMode.ECO))
        database.close()
        database = openDatabase()
        val row = withTimeout(5_000) { database.tripDao().observeSnapshots().first().single() }
        assertEquals(id, row.id)
        assertEquals(4.5, row.distanceKm, 0.0)
        assertEquals("ECO", row.drivingMode)
    }

    @Test
    fun deleteOnlyRemovesTheSelectedSnapshot() = runBlocking {
        val dao = database.tripDao()
        val first = dao.insert(snapshot(1_000, 1.0))
        val second = dao.insert(snapshot(2_000, 2.0))
        TripRepository(dao).deleteSnapshot(first)
        assertEquals(listOf(second), dao.observeSnapshots().first().map { it.id })
        assertEquals(1, dao.countSnapshots())
        dao.deleteSnapshot(second)
        assertTrue(dao.observeSnapshots().first().isEmpty())
    }

    @Test
    fun repositoryRejectsEmptyTripWithoutWriting() = runBlocking {
        try {
            TripRepository(database.tripDao()).saveSnapshot(VehicleStatus())
            throw AssertionError("Expected empty trip rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, database.tripDao().countSnapshots())
        }
    }

    private fun snapshot(timestamp: Long, distance: Double) = TripSnapshot(
        savedAtMillis = timestamp,
        distanceKm = distance,
        remainingEnergyKwh = 25.0,
        drivingMode = "SPORT"
    )
}
