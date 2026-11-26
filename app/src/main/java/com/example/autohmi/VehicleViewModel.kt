package com.example.autohmi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HistoryState(
    val snapshots: List<TripSnapshot> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val completedNavigationTrips: List<CompletedNavigationTrip> = emptyList(),
    val completedTripsLoading: Boolean = isLoading,
    val completedTripsError: Boolean = false
)

enum class VehicleMessage {
    SNAPSHOT_SAVED, SNAPSHOT_DELETED, SAVE_FAILED, DELETE_FAILED, SETTINGS_FAILED
}

class VehicleViewModel(
    private val settingsRepository: VehicleSettingsRepository,
    private val tripRepository: TripRepository,
    private val simulator: VehicleSimulator = VehicleSimulator()
) : ViewModel() {
    private val _vehicleStatus = MutableStateFlow(VehicleStatus())
    val vehicleStatus = _vehicleStatus.asStateFlow()

    private val _history = MutableStateFlow(HistoryState())
    val history = _history.asStateFlow()

    private val _settingsReady = MutableStateFlow(false)
    val settingsReady = _settingsReady.asStateFlow()
    private val _settingsError = MutableStateFlow(false)
    val settingsError = _settingsError.asStateFlow()
    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    private val messageChannel = Channel<VehicleMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var isForeground = false
    private var simulationJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.drivingMode
                .retryWhen { cause, _ ->
                    if (cause !is IOException) throw cause
                    _settingsError.value = true
                    _settingsReady.value = false
                    delay(1_000)
                    true
                }
                .collect { savedMode ->
                    // Only DataStore emissions change the selected preference.
                    _vehicleStatus.update { it.copy(drivingMode = savedMode) }
                    _settingsError.value = false
                    _settingsReady.value = true
                }
        }
        viewModelScope.launch {
            tripRepository.snapshots
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) throw cause
                    _history.update { it.copy(isLoading = false, hasError = true) }
                    delay(2_000)
                    true
                }
                .collect { rows -> _history.update { it.copy(snapshots = rows, isLoading = false, hasError = false) } }
        }
        viewModelScope.launch {
            tripRepository.completedNavigationTrips
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) throw cause
                    _history.update { it.copy(completedTripsLoading = false, completedTripsError = true) }
                    delay(2_000)
                    true
                }
                .collect { rows -> _history.update { it.copy(completedNavigationTrips = rows, completedTripsLoading = false, completedTripsError = false) } }
        }
    }

    fun accelerate() = _vehicleStatus.update(simulator::accelerate)
    fun brake() = _vehicleStatus.update(simulator::brake)
    fun resetTrip() = _vehicleStatus.update(simulator::resetTrip)

    fun toggleSimulation() {
        _vehicleStatus.update {
            it.copy(isSimulationRunning = !it.isSimulationRunning && it.batteryEnergyKwh > 0.0)
        }
        updateSimulationLoop()
    }

    // The Activity calls this independently of which tab is selected.
    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        updateSimulationLoop()
    }

    private fun updateSimulationLoop() {
        if (!isForeground || !_vehicleStatus.value.isSimulationRunning) {
            simulationJob?.cancel()
            simulationJob = null
            return
        }
        if (simulationJob?.isActive == true) return
        simulationJob = viewModelScope.launch {
            while (isActive && _vehicleStatus.value.isSimulationRunning) {
                delay(TICK_INTERVAL_MILLIS)
                _vehicleStatus.update { current ->
                    simulator.advance(current, SIMULATED_SECONDS_PER_TICK)
                }
            }
        }
    }

    fun changeDrivingMode(mode: DrivingMode) {
        if (!_settingsReady.value) return
        viewModelScope.launch {
            try {
                settingsRepository.saveDrivingMode(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                messageChannel.send(VehicleMessage.SETTINGS_FAILED)
            }
        }
    }

    fun saveTripSnapshot() {
        val current = _vehicleStatus.value
        if (current.distanceKm <= 0.0 || _isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                tripRepository.saveSnapshot(current)
                messageChannel.send(VehicleMessage.SNAPSHOT_SAVED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageChannel.send(VehicleMessage.SAVE_FAILED)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteSnapshot(id: Long) {
        if (id <= 0) return
        viewModelScope.launch {
            try {
                tripRepository.deleteSnapshot(id)
                messageChannel.send(VehicleMessage.SNAPSHOT_DELETED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageChannel.send(VehicleMessage.DELETE_FAILED)
            }
        }
    }
}
