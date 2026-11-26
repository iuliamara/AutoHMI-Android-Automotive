package com.example.autohmi.navigation

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autohmi.BuildConfig
import com.example.autohmi.CompletedTripRecorder

enum class MapMode { LIVE, OFFLINE_TEST }
data class NavigationDependencies(val locations: LocationRepository, val routes: RouteRepository, val mapMode: MapMode = MapMode.LIVE,
    val completedTripRecorder: CompletedTripRecorder? = null)
interface NavigationOwner {
    fun createNavigationDependencies(): NavigationDependencies
    val completedTripRecorder: CompletedTripRecorder? get() = null
}

class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val dependencies = (application as NavigationOwner).createNavigationDependencies()
    val controller = NavigationController(dependencies.locations, dependencies.routes, viewModelScope, SystemClock::elapsedRealtime,
        completedTripRecorder = dependencies.completedTripRecorder)
    val state = controller.state
    val mapMode = dependencies.mapMode
    // Camera is kept in RAM across ordinary tab navigation and Activity recreation, never location history on disk.
    var camera = MapCamera()
        private set
    fun saveCamera(camera: MapCamera) { this.camera = camera }
    override fun onCleared() { controller.close() }
}

fun productionNavigationDependencies(application: Application): NavigationDependencies = NavigationDependencies(
    FusedLocationRepository(application),
    if (BuildConfig.NAVIGATION_OSRM_BASE_URL.isBlank()) UnconfiguredRouteRepository()
    else OsrmRouteRepository(BuildConfig.NAVIGATION_OSRM_BASE_URL, elapsed = SystemClock::elapsedRealtime),
    completedTripRecorder = (application as NavigationOwner).completedTripRecorder
)
