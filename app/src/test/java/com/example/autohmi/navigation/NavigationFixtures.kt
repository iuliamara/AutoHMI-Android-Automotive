package com.example.autohmi.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Synthetic L-shaped route geometry for tests, not a claimed real-world road route. */
internal val fixtureOrigin = GeoPoint(52.0, 13.0)
internal val fixtureDestination = Destination(GeoPoint(52.001, 13.001), "Offline destination fixture")
internal fun fixtureRoute(origin: GeoPoint = fixtureOrigin, destination: Destination = fixtureDestination): RoutePlan {
    val geometry = listOf(origin, GeoPoint(origin.latitude, destination.point.longitude), destination.point)
    val length = GeoMath.project(origin, geometry).lengthMeters
    return RoutePlan(origin, destination, geometry, length * 1.02, length / 10, "Offline test fixture only")
}
internal class FakeLocations : LocationRepository {
    val current = MutableStateFlow(LocationState())
    override val state = current.asStateFlow()
    var tracking = false
    var closed = false
    var refreshes = 0
    override fun setPermission(permission: LocationPermission) { current.value = current.value.copy(permission = permission) }
    override fun setActive(active: Boolean) { tracking = active }
    override fun requestFresh() { refreshes++ }
    override fun close() { closed = true; tracking = false }
    fun fix(point: GeoPoint, elapsed: Long, accuracy: Double = 5.0, permission: LocationPermission = LocationPermission.PRECISE) {
        current.value = LocationState(permission, LocationStatus.AVAILABLE, LocationFix(point, accuracy, elapsed, simulated = true))
    }
}
internal class FakeRoutes(override val configured: Boolean = true) : RouteRepository {
    var calls = 0
    var handler: suspend (GeoPoint, Destination) -> RouteResult = { origin, destination -> RouteResult.Success(fixtureRoute(origin, destination)) }
    override suspend fun calculate(origin: GeoPoint, destination: Destination): RouteResult { calls++; return handler(origin, destination) }
}
