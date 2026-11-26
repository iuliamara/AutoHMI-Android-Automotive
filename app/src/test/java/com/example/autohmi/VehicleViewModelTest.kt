package com.example.autohmi

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var preferences: TestPreferences
    private lateinit var dao: TestTripDao
    private lateinit var viewModel: VehicleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        preferences = TestPreferences()
        dao = TestTripDao()
        recreateViewModel()
    }

    private fun recreateViewModel() {
        store.clear()
        viewModel = VehicleViewModel(VehicleSettingsRepository(preferences), TripRepository(dao) { 1_000L })
        store.put("vehicle", viewModel)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun foregroundChangesNeverCreateTwoLoopsOrCatchUpBackgroundTime() = runTest(dispatcher) {
        runCurrent()
        repeat(6) { viewModel.accelerate() }
        viewModel.setForeground(true)
        viewModel.toggleSimulation()
        repeat(3) { viewModel.setForeground(true) }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1.0, viewModel.vehicleStatus.value.distanceKm, 0.000001)

        viewModel.setForeground(false)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1.0, viewModel.vehicleStatus.value.distanceKm, 0.000001)
        assertTrue(viewModel.vehicleStatus.value.isSimulationRunning)

        viewModel.setForeground(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2.0, viewModel.vehicleStatus.value.distanceKm, 0.000001)
        assertEquals(60, viewModel.vehicleStatus.value.speedKmh)
    }

    @Test
    fun pauseAndRapidToggleCancelPendingTicks() = runTest(dispatcher) {
        viewModel.setForeground(true)
        viewModel.accelerate()
        repeat(10) { viewModel.toggleSimulation() }
        advanceTimeBy(5_000); runCurrent()
        assertFalse(viewModel.vehicleStatus.value.isSimulationRunning)
        assertEquals(0.0, viewModel.vehicleStatus.value.distanceKm, 0.0)
    }

    @Test
    fun datastoreRemainsAuthoritativeAndFailedWritesLeaveSelectionIntact() = runTest(dispatcher) {
        preferences.mode("SPORT")
        runCurrent()
        assertTrue(viewModel.settingsReady.value)
        assertEquals(DrivingMode.SPORT, viewModel.vehicleStatus.value.drivingMode)
        viewModel.changeDrivingMode(DrivingMode.ECO)
        assertEquals(DrivingMode.SPORT, viewModel.vehicleStatus.value.drivingMode)
        runCurrent()
        assertEquals(DrivingMode.ECO, viewModel.vehicleStatus.value.drivingMode)
        preferences.failWrites = true
        viewModel.changeDrivingMode(DrivingMode.NORMAL)
        runCurrent()
        assertEquals(DrivingMode.ECO, viewModel.vehicleStatus.value.drivingMode)
        assertEquals(VehicleMessage.SETTINGS_FAILED, viewModel.messages.first())
    }

    @Test
    fun unknownSavedModeFallsBackToNormal() = runTest(dispatcher) {
        preferences.mode("FUTURE_MODE")
        runCurrent()
        assertEquals(DrivingMode.NORMAL, viewModel.vehicleStatus.value.drivingMode)
    }

    @Test
    fun snapshotsAreGuardedAndResetPreservesSavedHistoryAndEnergy() = runTest(dispatcher) {
        runCurrent()
        viewModel.saveTripSnapshot()
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
        repeat(6) { viewModel.accelerate() }
        viewModel.setForeground(true)
        viewModel.toggleSimulation()
        advanceTimeBy(1_000); runCurrent()
        viewModel.toggleSimulation()
        viewModel.saveTripSnapshot()
        viewModel.saveTripSnapshot()
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        assertEquals(1.0, dao.rows.value.single().distanceKm, 0.000001)
        val energy = viewModel.vehicleStatus.value.batteryEnergyKwh
        viewModel.resetTrip()
        assertEquals(0.0, viewModel.vehicleStatus.value.distanceKm, 0.0)
        assertEquals(energy, viewModel.vehicleStatus.value.batteryEnergyKwh, 0.0)
        assertEquals(1, viewModel.history.value.snapshots.size)
        viewModel.deleteSnapshot(1L)
        runCurrent()
        assertTrue(viewModel.history.value.snapshots.isEmpty())
    }

    @Test
    fun failedInsertReportsAnErrorAndAllowsRetry() = runTest(dispatcher) {
        viewModel.setForeground(true)
        viewModel.accelerate()
        viewModel.toggleSimulation()
        advanceTimeBy(1_000); runCurrent()
        viewModel.toggleSimulation()
        dao.failWrites = true
        viewModel.saveTripSnapshot()
        runCurrent()
        assertEquals(VehicleMessage.SAVE_FAILED, viewModel.messages.first())
        assertFalse(viewModel.isSaving.value)
        dao.failWrites = false
        viewModel.saveTripSnapshot()
        runCurrent()
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun depletedBatteryStopsTheLoopAndCannotRestart() = runTest(dispatcher) {
        viewModel.setForeground(true)
        repeat(18) { viewModel.accelerate() }
        viewModel.toggleSimulation()
        advanceTimeBy(106_000); runCurrent()
        val depleted = viewModel.vehicleStatus.value
        assertEquals(0.0, depleted.batteryEnergyKwh, 0.0)
        assertEquals(0, depleted.speedKmh)
        assertFalse(depleted.isSimulationRunning)
        viewModel.toggleSimulation()
        viewModel.accelerate()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(depleted, viewModel.vehicleStatus.value)
    }

    @Test
    fun preferenceReadErrorIsVisibleAndRecoversWithoutOverwritingSavedMode() = runTest(dispatcher) {
        preferences.mode("SPORT")
        preferences.readFailures = 1
        recreateViewModel()
        runCurrent()
        assertTrue(viewModel.settingsError.value)
        assertFalse(viewModel.settingsReady.value)
        viewModel.changeDrivingMode(DrivingMode.ECO)
        advanceTimeBy(1_000); runCurrent()
        assertFalse(viewModel.settingsError.value)
        assertTrue(viewModel.settingsReady.value)
        assertEquals(DrivingMode.SPORT, viewModel.vehicleStatus.value.drivingMode)
    }

    @Test
    fun historyReadErrorRecoversAndFailedDeletionPreservesTheSnapshot() = runTest(dispatcher) {
        dao.rows.value = listOf(TripSnapshot(1, 1_000, 2.0, 38.0, "NORMAL"))
        dao.readFailures = 1
        recreateViewModel()
        runCurrent()
        assertTrue(viewModel.history.value.hasError)
        advanceTimeBy(2_000); runCurrent()
        assertFalse(viewModel.history.value.hasError)
        assertEquals(1, viewModel.history.value.snapshots.size)
        dao.failWrites = true
        viewModel.deleteSnapshot(1)
        runCurrent()
        assertEquals(VehicleMessage.DELETE_FAILED, viewModel.messages.first())
        assertEquals(1, viewModel.history.value.snapshots.size)
    }

    private class TestPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow {
            if (readFailures > 0) {
                readFailures--
                throw IOException("Test preference read failure")
            }
            emitAll(state)
        }
        var failWrites = false
        var readFailures = 0

        fun mode(mode: String) {
            state.value = state.value.toMutablePreferences().apply {
                this[stringPreferencesKey("driving_mode")] = mode
            }
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("Test write failure")
            return transform(state.value).also { state.value = it }
        }
    }

    private class TestTripDao : TripDao {
        val rows = MutableStateFlow<List<TripSnapshot>>(emptyList())
        var failWrites = false
        var readFailures = 0
        override suspend fun insert(snapshot: TripSnapshot): Long {
            if (failWrites) throw IOException("Test insert failure")
            val id = rows.value.size.toLong() + 1
            rows.value += snapshot.copy(id = id)
            return id
        }
        override fun observeSnapshots(): Flow<List<TripSnapshot>> = flow {
            if (readFailures > 0) {
                readFailures--
                throw IOException("Test history read failure")
            }
            emitAll(rows)
        }
        override fun observeRecentSnapshots(): Flow<List<TripSnapshot>> = rows.map { it.take(5) }
        override suspend fun countSnapshots(): Int = rows.value.size
        override suspend fun deleteSnapshot(id: Long) {
            if (failWrites) throw IOException("Test deletion failure")
            rows.value = rows.value.filterNot { it.id == id }
        }
    }
}
