package com.example.autohmi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerTest {
    private data class Fixture(val controller: NavigationController, val locations: FakeLocations, val routes: FakeRoutes)
    private fun TestScope.fixture(configured: Boolean = true): Fixture {
        val locations = FakeLocations(); val routes = FakeRoutes(configured)
        val controller = NavigationController(locations, routes, backgroundScope, { BASE + testScheduler.currentTime }, { 1_000_000 + testScheduler.currentTime })
        controller.setActive(true)
        locations.fix(fixtureOrigin, BASE + testScheduler.currentTime)
        runCurrent()
        return Fixture(controller, locations, routes)
    }
    @Test fun selectingChangingAndClearingDestinationInvalidatesTheOldRoute() = runTest {
        val (controller, _, _) = fixture()
        controller.selectDestination(fixtureDestination.point, fixtureDestination.name)
        assertEquals(TripPhase.DESTINATION_SELECTED, controller.state.value.phase)
        controller.calculateRoute(); runCurrent()
        assertEquals(TripPhase.ROUTE_READY, controller.state.value.phase)
        controller.selectDestination(GeoPoint(52.002, 13.002), "Changed")
        assertNull(controller.state.value.route)
        controller.clearDestination()
        assertEquals(TripPhase.IDLE, controller.state.value.phase)
        assertNull(controller.state.value.destination)
    }
    @Test fun noConfiguredProviderCannotInventADrivingRoute() = runTest {
        val (controller, _, routes) = fixture(configured = false)
        controller.selectDestination(fixtureDestination.point)
        controller.calculateRoute(); runCurrent()
        assertEquals(0, routes.calls)
        assertEquals(NavigationIssue.ROUTING_NOT_CONFIGURED, controller.state.value.issue)
        assertNull(controller.state.value.route)
        controller.startTrip()
        assertNotEquals(TripPhase.TRIP_ACTIVE, controller.state.value.phase)
    }
    @Test fun snappedRoadEndpointCanBeFarFromTheSelectedPinWithoutInflatingMetrics() = runTest {
        val (controller, _, routes) = fixture()
        val selected = Destination(GeoPoint(52.005, 13.001), "Selected off-road point")
        routes.handler = { origin, target -> RouteResult.Success(RoutePlan(origin, target,
            listOf(origin, GeoPoint(52.0, 13.001), fixtureDestination.point), 200.0, 60.0,
            snappedDestination = fixtureDestination.point)) }
        controller.selectDestination(selected.point, selected.name)
        controller.calculateRoute(); runCurrent()
        assertEquals(TripPhase.ROUTE_READY, controller.state.value.phase)
        val route = requireNotNull(controller.state.value.route)
        assertEquals(selected, controller.state.value.destination)
        assertEquals(fixtureDestination.point, route.drivingEndpoint)
        assertTrue(route.destinationGapMeters > 150)
        assertTrue(route.hasDestinationGap)
        assertEquals(200.0, route.distanceMeters, 0.0)
        assertEquals(60.0, route.durationSeconds, 0.0)
    }
    @Test fun missingDeniedDisabledAndStaleLocationsCannotPlan() = runTest {
        for (status in listOf(LocationStatus.DENIED, LocationStatus.SERVICES_DISABLED, LocationStatus.UNAVAILABLE)) {
            val (controller, locations, routes) = fixture()
            locations.current.value = LocationState(LocationPermission.NONE, status)
            runCurrent()
            controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
            assertEquals(0, routes.calls)
            assertNull(controller.state.value.location.fix)
        }
        val (controller, _, routes) = fixture()
        controller.selectDestination(fixtureDestination.point)
        advanceTimeBy(16_001); runCurrent(); controller.calculateRoute(); runCurrent()
        assertEquals(NavigationIssue.STALE_LOCATION, controller.state.value.issue)
        assertEquals(0, routes.calls)
    }
    @Test fun approximateLocationMayPlanButCannotStartAnArrivalTrackingTrip() = runTest {
        val (controller, locations, _) = fixture()
        advanceTimeBy(1)
        locations.fix(fixtureOrigin, BASE + testScheduler.currentTime, 800.0, LocationPermission.APPROXIMATE)
        runCurrent()
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
        assertEquals(TripPhase.ROUTE_READY, controller.state.value.phase)
        controller.startTrip()
        assertEquals(NavigationIssue.PRECISE_REQUIRED, controller.state.value.issue)
        assertEquals(TripPhase.ROUTE_READY, controller.state.value.phase)
    }
    @Test fun poorAccuracyCannotEnableTripProgress() = runTest {
        val (controller, locations, _) = fixture()
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
        advanceTimeBy(1); locations.fix(fixtureOrigin, BASE + testScheduler.currentTime, 90.0); runCurrent()
        controller.startTrip()
        assertEquals(NavigationIssue.LOW_ACCURACY, controller.state.value.issue)
        assertNotEquals(TripPhase.TRIP_ACTIVE, controller.state.value.phase)
    }
    @Test fun startProgressArrivalAndEndUseFreshRoadGeometry() = runTest {
        val (controller, locations, routes) = fixture()
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent(); controller.startTrip()
        assertEquals(TripPhase.TRIP_ACTIVE, controller.state.value.phase)
        val initial = requireNotNull(controller.state.value.remainingMeters)
        advanceTimeBy(2_000); locations.fix(GeoPoint(52.0, 13.0008), BASE + testScheduler.currentTime); runCurrent()
        assertTrue(requireNotNull(controller.state.value.remainingMeters) < initial)
        assertNotNull(controller.state.value.estimatedArrivalEpochMs)
        repeat(2) { advanceTimeBy(2_000); locations.fix(fixtureDestination.point, BASE + testScheduler.currentTime); runCurrent() }
        assertEquals(TripPhase.TRIP_ACTIVE, controller.state.value.phase)
        advanceTimeBy(2_000); locations.fix(fixtureDestination.point, BASE + testScheduler.currentTime); runCurrent()
        assertEquals(TripPhase.ARRIVED, controller.state.value.phase)
        assertEquals(0.0, controller.state.value.remainingMeters)
        assertEquals(1, routes.calls) // GPS movement never automatically reroutes.
        controller.endTrip()
        assertEquals(TripPhase.DESTINATION_SELECTED, controller.state.value.phase)
        assertNull(controller.state.value.route)
    }
    @Test fun staleGapAndTeleportCannotDeclareArrival() = runTest {
        val (controller, locations, _) = fixture()
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent(); controller.startTrip()
        advanceTimeBy(16_001); runCurrent()
        assertNull(controller.state.value.estimatedArrivalEpochMs)
        repeat(3) { advanceTimeBy(2_000); locations.fix(fixtureDestination.point, BASE + testScheduler.currentTime); runCurrent() }
        assertEquals(TripPhase.TRIP_ACTIVE, controller.state.value.phase)
        assertEquals(NavigationIssue.GPS_JUMP, controller.state.value.issue)
        assertNull(controller.state.value.remainingMeters)
        controller.endTrip()
        assertEquals(fixtureDestination.point, controller.state.value.location.fix?.point)
    }
    @Test fun suddenJumpIsIgnoredAndNeverOverwritesTheAcceptedMarker() = runTest {
        val (controller, locations, _) = fixture()
        advanceTimeBy(2_000); locations.fix(GeoPoint(53.0, 14.0), BASE + testScheduler.currentTime); runCurrent()
        assertEquals(fixtureOrigin, controller.state.value.location.fix?.point)
        assertEquals(NavigationIssue.GPS_JUMP, controller.state.value.issue)
    }
    @Test fun offRoutePausesEstimatesWithoutUnrequestedNetworkCalls() = runTest {
        val (controller, locations, routes) = fixture()
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent(); controller.startTrip()
        advanceTimeBy(3_000); locations.fix(GeoPoint(51.999, 13.001), BASE + testScheduler.currentTime); runCurrent()
        assertEquals(NavigationIssue.OFF_ROUTE, controller.state.value.issue)
        assertNull(controller.state.value.estimatedArrivalEpochMs)
        assertEquals(1, routes.calls)
    }
    @Test fun gpsNoiseNearTheOtherEndOfALoopCannotSkipTheRoute() = runTest {
        val (controller, locations, routes) = fixture()
        val destination = Destination(GeoPoint(52.0001, 13.0), "Offline loop fixture")
        routes.handler = { origin, target ->
            val geometry = listOf(origin, GeoPoint(52.0, 13.004), GeoPoint(52.004, 13.004), GeoPoint(52.004, 13.0), target.point)
            RouteResult.Success(RoutePlan(origin, target, geometry, 1500.0, 180.0, "Offline loop test only"))
        }
        controller.selectDestination(destination.point); controller.calculateRoute(); runCurrent(); controller.startTrip()
        repeat(3) { advanceTimeBy(2_000); locations.fix(destination.point, BASE + testScheduler.currentTime); runCurrent() }
        assertNotEquals(TripPhase.ARRIVED, controller.state.value.phase)
        assertEquals(NavigationIssue.GPS_JUMP, controller.state.value.issue)
    }
    @Test fun changedOriginAndInvalidProviderGeometryCannotStartATrip() = runTest {
        val (controller, locations, _) = fixture()
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
        advanceTimeBy(10_000); locations.fix(GeoPoint(52.002, 13.0), BASE + testScheduler.currentTime); runCurrent()
        assertNull(controller.state.value.route)
        assertEquals(NavigationIssue.ORIGIN_MOVED, controller.state.value.issue)
        val other = fixture()
        other.routes.handler = { origin, target -> RouteResult.Success(RoutePlan(origin, target, listOf(GeoPoint(0.0, 0.0), GeoPoint(0.1, 0.1)), 10.0, 1.0)) }
        other.controller.selectDestination(fixtureDestination.point); other.controller.calculateRoute(); runCurrent()
        assertEquals(NavigationIssue.INVALID_ROUTE, other.controller.state.value.issue)
    }
    @Test fun leavingScreenCancelsRouteWorkAndStopsLocationAndClosingCleansUp() = runTest {
        val (controller, locations, routes) = fixture()
        val gate = CompletableDeferred<Unit>()
        routes.handler = { origin, target -> withContext(NonCancellable) { gate.await() }; RouteResult.Success(fixtureRoute(origin, target)) }
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
        assertEquals(TripPhase.CALCULATING, controller.state.value.phase)
        controller.setActive(false)
        assertFalse(locations.tracking)
        gate.complete(Unit); runCurrent()
        assertEquals(TripPhase.DESTINATION_SELECTED, controller.state.value.phase)
        assertNull(controller.state.value.route)
        controller.calculateRoute(); runCurrent()
        assertEquals(1, routes.calls)
        controller.close()
        assertTrue(locations.closed)
    }
    @Test fun destinationChangeRejectsALateRouteAndCameraCommandsRespectExploration() = runTest {
        val (controller, _, routes) = fixture(); val gate = CompletableDeferred<Unit>()
        routes.handler = { origin, target -> withContext(NonCancellable) { gate.await() }; RouteResult.Success(fixtureRoute(origin, target)) }
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
        val replacement = GeoPoint(52.002, 13.002)
        controller.selectDestination(replacement); gate.complete(Unit); runCurrent()
        assertEquals(replacement, controller.state.value.destination?.point)
        assertNull(controller.state.value.route)
        controller.manuallyExploring(); assertFalse(controller.state.value.followLocation)
        controller.recenter(); assertTrue(controller.state.value.followLocation)
        controller.overview(); assertFalse(controller.state.value.followLocation)
        controller.setTheme(MapTheme.LIGHT); assertEquals(MapTheme.LIGHT, controller.state.value.theme)
    }
    @Test fun unavailableRouteAndConfigurableArrivalRadiusRemainExplicit() = runTest {
        val (controller, _, routes) = fixture()
        routes.handler = { _, _ -> RouteResult.Failure(NavigationIssue.NO_ROUTE) }
        controller.selectDestination(fixtureDestination.point); controller.calculateRoute(); runCurrent()
        assertEquals(TripPhase.ERROR, controller.state.value.phase)
        assertEquals(NavigationIssue.NO_ROUTE, controller.state.value.issue)
        controller.setArrivalRadius(50.0); assertEquals(50.0, controller.state.value.arrivalRadiusMeters, 0.0)
        try { controller.setArrivalRadius(1000.0); fail("Unreasonable arrival threshold accepted") } catch (_: IllegalArgumentException) { }
    }
    companion object { private const val BASE = 100_000L }
}
