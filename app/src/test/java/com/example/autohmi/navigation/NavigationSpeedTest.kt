package com.example.autohmi.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSpeedTest {
    private fun measurement(speed: Double? = 10.0, at: Long = BASE, accuracy: Double = 5.0, uncertainty: Double? = 0.5,
        point: GeoPoint = fixtureOrigin) = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE,
        LocationFix(point, accuracy, at, simulated = true, speedMetersPerSecond = speed, speedAccuracyMetersPerSecond = uncertainty))

    @Test fun reportedMetersPerSecondConvertToKmhAndMeasuredZeroRemainsZero() {
        assertEquals(36.0, requireNotNull(measurement(10.0).validGpsSpeedKmh(BASE)), 0.0)
        assertEquals(30.0, requireNotNull(measurement(30.0 / 3.6).validGpsSpeedKmh(BASE)), 0.000001)
        assertEquals(0.0, requireNotNull(measurement(0.0).validGpsSpeedKmh(BASE)), 0.0)
        assertNull(measurement(null).validGpsSpeedKmh(BASE))
    }
    @Test fun staleAndFutureMeasurementsAreUnavailableAtTheExistingFreshnessBoundary() {
        val location = measurement()
        assertNotNull(location.validGpsSpeedKmh(BASE + 15_000))
        assertNull(location.validGpsSpeedKmh(BASE + 15_001))
        assertNull(location.validGpsSpeedKmh(BASE - 1))
    }
    @Test fun invalidAndUnreliableMetadataNeverInventASpeed() {
        for (speed in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, 100.01)) assertNull(measurement(speed).validGpsSpeedKmh(BASE))
        for (uncertainty in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, 2.01)) {
            val location = measurement(uncertainty = uncertainty)
            assertEquals(fixtureOrigin, location.fix?.point) // Speed quality does not discard coordinates.
            assertNull(location.validGpsSpeedKmh(BASE))
        }
        assertNotNull(measurement(accuracy = 50.0, uncertainty = 2.0).validGpsSpeedKmh(BASE))
        assertNull(measurement(accuracy = 50.01).validGpsSpeedKmh(BASE))
        assertNotNull(measurement(uncertainty = null).validGpsSpeedKmh(BASE)) // Accuracy metadata is optional on Android.
        for (status in LocationStatus.entries.filter { it != LocationStatus.AVAILABLE }) assertNull(measurement().copy(status = status).validGpsSpeedKmh(BASE))
        for (permission in listOf(LocationPermission.NONE, LocationPermission.APPROXIMATE)) assertNull(measurement().copy(permission = permission).validGpsSpeedKmh(BASE))
        assertNull(LocationState().validGpsSpeedKmh(BASE))
    }
    @Test fun demoAlwaysTakesPriorityAndGpsIsTheDefaultWithoutARoute() {
        val gps = NavigationState(gpsSpeedKmh = 36.0)
        assertNull(gps.route); assertEquals(NavigationSpeed(SpeedSource.GPS, 36.0), gps.speedometer)
        for (status in DemoDriveStatus.entries) {
            val state = gps.copy(demoDrive = DemoDriveState(fixtureOrigin, 0.0, 100.0, status))
            assertEquals(SpeedSource.DEMO, state.speedometer.source)
            assertEquals(if (status == DemoDriveStatus.RUNNING) 30.0 else 0.0, requireNotNull(state.speedometer.speedKmh), 0.0)
            assertEquals(gps.speedometer, state.copy(demoDrive = null).speedometer)
        }
        assertEquals(NavigationSpeed(SpeedSource.GPS, null), NavigationState().speedometer)
    }

    private data class Fixture(val controller: NavigationController, val locations: FakeLocations, val routes: FakeRoutes)
    private fun TestScope.fixture(speed: Double? = 10.0): Fixture {
        val locations = FakeLocations(); val routes = FakeRoutes()
        val controller = NavigationController(locations, routes, backgroundScope, { BASE + testScheduler.currentTime })
        controller.setActive(true)
        locations.current.value = measurement(speed, BASE + testScheduler.currentTime); runCurrent()
        return Fixture(controller, locations, routes)
    }
    @Test fun existingControllerTickExpiresSpeedWithoutANewFixOrActiveRoute() = runTest {
        val f = fixture(); val reported = f.locations.current.value
        assertEquals(NavigationSpeed(SpeedSource.GPS, 36.0), f.controller.state.value.speedometer)
        assertNull(f.controller.state.value.route)
        advanceTimeBy(15_000); runCurrent(); assertEquals(36.0, requireNotNull(f.controller.state.value.speedometer.speedKmh), 0.0)
        advanceTimeBy(1000); runCurrent(); assertEquals(NavigationSpeed(SpeedSource.GPS, null), f.controller.state.value.speedometer)
        assertEquals(reported, f.locations.current.value)
        f.locations.current.value = measurement(5.0, BASE + testScheduler.currentTime); runCurrent()
        assertEquals(NavigationSpeed(SpeedSource.GPS, 18.0), f.controller.state.value.speedometer)
        assertEquals(0, f.routes.calls)
    }
    @Test fun permissionAndAvailabilityLossImmediatelySuppressRetainedGpsSpeed() = runTest {
        val f = fixture()
        f.locations.current.value = f.locations.current.value.copy(status = LocationStatus.SERVICES_DISABLED); runCurrent()
        assertNull(f.controller.state.value.speedometer.speedKmh)
        assertEquals(fixtureOrigin, f.controller.state.value.location.fix?.point)
        f.locations.current.value = measurement(); runCurrent()
        assertEquals(36.0, requireNotNull(f.controller.state.value.speedometer.speedKmh), 0.0)
        f.locations.setPermission(LocationPermission.APPROXIMATE); runCurrent(); assertNull(f.controller.state.value.speedometer.speedKmh)
        f.locations.setPermission(LocationPermission.NONE); runCurrent(); assertNull(f.controller.state.value.speedometer.speedKmh)
    }
    @Test fun rejectedGpsJumpSuppressesSpeedUntilAFreshFixIsAccepted() = runTest {
        val f = fixture(); advanceTimeBy(2000)
        f.locations.current.value = measurement(at = BASE + testScheduler.currentTime, point = GeoPoint(53.0, 14.0)); runCurrent()
        assertEquals(fixtureOrigin, f.controller.state.value.location.fix?.point)
        assertNull(f.controller.state.value.speedometer.speedKmh)
        advanceTimeBy(2000)
        f.locations.current.value = measurement(7.5, BASE + testScheduler.currentTime); runCurrent()
        assertEquals(NavigationSpeed(SpeedSource.GPS, 27.0), f.controller.state.value.speedometer)
        assertEquals(0, f.routes.calls)
    }
    @Test fun demoExitReturnsToFreshGpsOrUnknownWhenTheGpsMeasurementHasExpired() = runTest {
        val f = fixture()
        val end = GeoPoint(fixtureOrigin.latitude + Math.toDegrees(95.0 / 6_371_008.8), fixtureOrigin.longitude)
        f.routes.handler = { origin, destination -> RouteResult.Success(RoutePlan(origin, destination, listOf(origin, end), 120.0, 60.0)) }
        f.controller.selectDestination(end); f.controller.calculateRoute(); runCurrent()
        f.controller.startDemoDrive(); runCurrent(); assertEquals(30.0, requireNotNull(f.controller.state.value.speedometer.speedKmh), 0.0)
        f.controller.stopDemoDrive(); assertEquals(NavigationSpeed(SpeedSource.DEMO, 0.0, DemoDriveStatus.PAUSED), f.controller.state.value.speedometer)
        advanceTimeBy(1000); f.locations.current.value = measurement(12.5, BASE + testScheduler.currentTime); runCurrent()
        assertEquals(SpeedSource.DEMO, f.controller.state.value.speedometer.source)
        f.controller.exitDemoDrive(); assertEquals(NavigationSpeed(SpeedSource.GPS, 45.0), f.controller.state.value.speedometer)
        f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(12_000); runCurrent()
        assertEquals(NavigationSpeed(SpeedSource.DEMO, 0.0, DemoDriveStatus.ARRIVED), f.controller.state.value.speedometer)
        advanceTimeBy(20_000); runCurrent(); f.controller.exitDemoDrive()
        assertEquals(NavigationSpeed(SpeedSource.GPS, null), f.controller.state.value.speedometer)
        assertEquals(1, f.routes.calls)
    }
    @Test fun coordinateMovementWithoutReportedSpeedDoesNotProduceTelemetry() = runTest {
        val f = fixture(speed = null)
        advanceTimeBy(2000)
        f.locations.current.value = measurement(null, BASE + testScheduler.currentTime, point = GeoPoint(52.0001, 13.0)); runCurrent()
        assertEquals(GeoPoint(52.0001, 13.0), f.controller.state.value.location.fix?.point)
        assertNull(f.controller.state.value.speedometer.speedKmh)
        assertEquals(0, f.routes.calls)
    }
    companion object { private const val BASE = 1_000_000L }
}
