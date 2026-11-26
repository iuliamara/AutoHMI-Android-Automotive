package com.example.autohmi.navigation

import kotlinx.coroutines.flow.StateFlow

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid geographic point" } }
    override fun toString() = "GeoPoint([location])"
}
data class LocationFix(val point: GeoPoint, val accuracyMeters: Double, val elapsedMs: Long, val simulated: Boolean = false,
    val speedMetersPerSecond: Double? = null, val speedAccuracyMetersPerSecond: Double? = null) {
    init { require(accuracyMeters.isFinite() && accuracyMeters >= 0 && elapsedMs >= 0) { "Invalid location fix" } }
}
enum class LocationPermission { NONE, APPROXIMATE, PRECISE }
enum class LocationStatus { IDLE, DENIED, SERVICES_DISABLED, SEARCHING, AVAILABLE, UNAVAILABLE, UNSUPPORTED }
data class LocationState(val permission: LocationPermission = LocationPermission.NONE, val status: LocationStatus = LocationStatus.IDLE, val fix: LocationFix? = null)
interface LocationRepository {
    val state: StateFlow<LocationState>
    fun setPermission(permission: LocationPermission)
    fun setActive(active: Boolean)
    fun requestFresh()
    fun close()
}

data class Destination(val point: GeoPoint, val name: String = "Pinned destination")
data class RoutePlan(
    val origin: GeoPoint,
    val destination: Destination,
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val attribution: String = "Routing: OSRM · © OpenStreetMap contributors",
    val snappedDestination: GeoPoint = geometry.last()
) {
    // The original destination stays separate from the road endpoint. This gap is
    // a straight-line separation, never added to driving metrics or treated as a path.
    val drivingEndpoint: GeoPoint get() = geometry.last()
    val destinationGapMeters: Double get() = GeoMath.distance(destination.point, snappedDestination)
    val hasDestinationGap: Boolean get() = destinationGapMeters > DESTINATION_GAP_WARNING_METERS
    init {
        require(geometry.size in 2..20_000 && distanceMeters.isFinite() && distanceMeters in 0.01..50_000_000.0 &&
            durationSeconds.isFinite() && durationSeconds in 0.01..7_776_000.0 && attribution.isNotBlank()) { "Invalid driving route" }
        require(GeoMath.distance(snappedDestination, drivingEndpoint) <= ENDPOINT_TOLERANCE_METERS) { "Inconsistent driving endpoint" }
    }
    companion object {
        const val DESTINATION_GAP_WARNING_METERS = 25.0
        const val ENDPOINT_TOLERANCE_METERS = 5.0
    }
}
enum class NavigationIssue {
    ROUTING_NOT_CONFIGURED, NO_LOCATION, STALE_LOCATION, PRECISE_REQUIRED, LOW_ACCURACY,
    NO_ROUTE, NETWORK, SERVER, RATE_LIMITED, INVALID_ROUTE, ORIGIN_MOVED, GPS_JUMP, OFF_ROUTE
}
sealed interface RouteResult {
    data class Success(val route: RoutePlan) : RouteResult
    data class Failure(val issue: NavigationIssue) : RouteResult
}
interface RouteRepository {
    val configured: Boolean
    suspend fun calculate(origin: GeoPoint, destination: Destination): RouteResult
}
class UnconfiguredRouteRepository : RouteRepository {
    override val configured = false
    override suspend fun calculate(origin: GeoPoint, destination: Destination) = RouteResult.Failure(NavigationIssue.ROUTING_NOT_CONFIGURED)
}

enum class TripPhase { IDLE, DESTINATION_SELECTED, CALCULATING, ROUTE_READY, TRIP_ACTIVE, ARRIVED, ERROR }
enum class MapTheme { DARK, LIGHT }
enum class DemoDriveStatus { RUNNING, PAUSED, ARRIVED }
enum class CompletionSaveStatus { NONE, SAVING, SAVED, FAILED }

/** DEMO position is owned by navigation; it is never published to LocationRepository. */
data class DemoDriveState(
    val position: GeoPoint,
    val traveledMeters: Double,
    val geometryLengthMeters: Double,
    val status: DemoDriveStatus = DemoDriveStatus.RUNNING
) {
    val progressFraction: Double get() = (traveledMeters / geometryLengthMeters).coerceIn(0.0, 1.0)
    val speedKmh: Double get() = if (status == DemoDriveStatus.RUNNING) SPEED_KMH else 0.0
    companion object { const val SPEED_KMH = 30.0 }
}
data class MapCamera(val center: GeoPoint = GeoPoint(0.0, 0.0), val zoom: Double = 1.5, val bearing: Double = 0.0, val tilt: Double = 0.0,
    val appliedRequestId: Long = 0L)
enum class CameraAction { RECENTER, OVERVIEW, ROUTE }
data class CameraRequest(val id: Long = 0, val action: CameraAction = CameraAction.RECENTER)
data class NavigationState(
    val location: LocationState = LocationState(),
    val phase: TripPhase = TripPhase.IDLE,
    val destination: Destination? = null,
    val route: RoutePlan? = null,
    val issue: NavigationIssue? = null,
    val followLocation: Boolean = true,
    val theme: MapTheme = MapTheme.DARK,
    val routingConfigured: Boolean = false,
    val remainingMeters: Double? = null,
    val remainingSeconds: Double? = null,
    val estimatedArrivalEpochMs: Long? = null,
    val arrivalRadiusMeters: Double = 35.0,
    val cameraRequest: CameraRequest = CameraRequest(),
    val demoDrive: DemoDriveState? = null,
    val gpsSpeedKmh: Double? = null,
    val completionSaveStatus: CompletionSaveStatus = CompletionSaveStatus.NONE
) {
    val mapVehiclePosition: GeoPoint? get() = demoDrive?.position ?: location.fix?.point
    val speedometer: NavigationSpeed get() = demoDrive?.let { NavigationSpeed(SpeedSource.DEMO, it.speedKmh, it.status) }
        ?: NavigationSpeed(SpeedSource.GPS, gpsSpeedKmh)
}
