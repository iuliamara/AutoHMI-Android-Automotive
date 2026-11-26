package com.example.autohmi.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoDriveTest {
    private data class Fixture(val controller: NavigationController, val locations: FakeLocations, val routes: FakeRoutes, val roadEnd: GeoPoint)
    private fun TestScope.fixture(gapMeters: Double = 0.0): Fixture {
        fun north(meters: Double) = GeoPoint(fixtureOrigin.latitude + Math.toDegrees(meters / 6_371_008.8), fixtureOrigin.longitude)
        val end = north(95.0)
        val locations = FakeLocations(); val routes = FakeRoutes()
        routes.handler = { origin, destination -> RouteResult.Success(RoutePlan(origin, destination,
            listOf(origin, north(10.0), end), 120.0, 60.0, "DEMO unit fixture only", end)) }
        val controller = NavigationController(locations, routes, backgroundScope, { BASE + testScheduler.currentTime }, { WALL + testScheduler.currentTime })
        controller.setActive(true); locations.fix(fixtureOrigin, BASE + testScheduler.currentTime); runCurrent()
        controller.selectDestination(north(95.0 + gapMeters), "Selected point")
        controller.calculateRoute(); runCurrent()
        assertEquals(TripPhase.ROUTE_READY, controller.state.value.phase)
        return Fixture(controller, locations, routes, end)
    }
    @Test fun fixedThirtyKmhUpdatesOncePerSecondWithoutChangingDeviceGpsOrFetchingRoutes() = runTest {
        val f = fixture(); val gps = f.locations.current.value; val route = f.controller.state.value.route
        f.controller.startDemoDrive(); runCurrent()
        assertEquals(fixtureOrigin, f.controller.state.value.demoDrive?.position)
        advanceTimeBy(999); runCurrent()
        assertEquals(0.0, requireNotNull(f.controller.state.value.demoDrive).traveledMeters, 0.0)
        advanceTimeBy(1); runCurrent()
        val state = f.controller.state.value; val demo = requireNotNull(state.demoDrive)
        assertEquals(30.0, demo.speedKmh, 0.0)
        assertEquals(30.0 / 3.6, demo.traveledMeters, 0.000001)
        assertEquals(demo.traveledMeters, GeoMath.distance(fixtureOrigin, demo.position), 0.001)
        assertEquals(120.0 * (1 - demo.progressFraction), requireNotNull(state.remainingMeters), 0.000001)
        assertEquals((demo.geometryLengthMeters - demo.traveledMeters) / (30.0 / 3.6), requireNotNull(state.remainingSeconds), 0.000001)
        assertNotNull(state.estimatedArrivalEpochMs)
        assertEquals(gps, state.location); assertEquals(gps, f.locations.current.value)
        assertEquals(demo.position, state.mapVehiclePosition)
        assertSame(route, state.route); assertEquals(120.0, requireNotNull(state.route).distanceMeters, 0.0)
        assertEquals(60.0, requireNotNull(state.route).durationSeconds, 0.0)
        f.controller.calculateRoute(); runCurrent(); assertEquals(1, f.routes.calls)
    }
    @Test fun arrivalIsAtTheActualGeometryEndpointWithoutCrossingTheDestinationGap() = runTest {
        val f = fixture(gapMeters = 100.0)
        f.controller.startDemoDrive(); runCurrent()
        advanceTimeBy(11_000); runCurrent()
        assertEquals(TripPhase.TRIP_ACTIVE, f.controller.state.value.phase)
        assertTrue(requireNotNull(f.controller.state.value.demoDrive).traveledMeters > 90)
        advanceTimeBy(1000); runCurrent()
        val arrived = f.controller.state.value
        assertEquals(TripPhase.ARRIVED, arrived.phase)
        assertEquals(DemoDriveStatus.ARRIVED, arrived.demoDrive?.status)
        assertEquals(0.0, requireNotNull(arrived.demoDrive).speedKmh, 0.0)
        assertEquals(f.roadEnd, arrived.demoDrive?.position)
        assertNotEquals(arrived.destination?.point, arrived.demoDrive?.position)
        assertTrue(requireNotNull(arrived.route).hasDestinationGap)
        assertEquals(0.0, requireNotNull(arrived.remainingMeters), 0.0)
        assertEquals(0.0, requireNotNull(arrived.remainingSeconds), 0.0)
        assertNull(arrived.estimatedArrivalEpochMs)
        assertEquals(1.0, requireNotNull(arrived.demoDrive).progressFraction, 0.0)
        advanceTimeBy(60_000); runCurrent()
        f.controller.stopDemoDrive(); f.controller.resumeDemoDrive(); f.controller.startDemoDrive()
        f.controller.setActive(false); f.controller.setActive(true); f.controller.setTheme(MapTheme.LIGHT)
        f.locations.fix(GeoPoint(52.1, 13.1), BASE + testScheduler.currentTime); runCurrent()
        assertEquals(arrived.demoDrive, f.controller.state.value.demoDrive)
        assertEquals(f.roadEnd, f.controller.state.value.mapVehiclePosition)
        assertEquals(TripPhase.ARRIVED, f.controller.state.value.phase)
        assertEquals(0.0, requireNotNull(f.controller.state.value.remainingMeters), 0.0)
        assertNull(f.controller.state.value.estimatedArrivalEpochMs)
        assertNull(f.controller.state.value.issue); assertEquals(1, f.routes.calls)
        f.controller.selectDestination(GeoPoint(52.002, 13.002), "New route destination")
        assertNull(f.controller.state.value.demoDrive); assertNull(f.controller.state.value.route)
        assertEquals(TripPhase.DESTINATION_SELECTED, f.controller.state.value.phase)
    }
    @Test fun stoppingPreservesPositionAndProgressAndResumeContinuesWithoutRestarting() = runTest {
        val f = fixture(); val originalGps = f.controller.state.value.location; val route = f.controller.state.value.route
        f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(2000); runCurrent()
        val moving = requireNotNull(f.controller.state.value.demoDrive)
        val remaining = f.controller.state.value.remainingMeters
        f.controller.stopDemoDrive()
        val stopped = f.controller.state.value
        assertEquals(moving.copy(status = DemoDriveStatus.PAUSED), stopped.demoDrive)
        assertEquals(0.0, requireNotNull(stopped.demoDrive).speedKmh, 0.0)
        assertEquals(moving.position, stopped.mapVehiclePosition); assertEquals(remaining, stopped.remainingMeters)
        assertEquals(originalGps, stopped.location); assertSame(route, stopped.route)
        assertNull(stopped.remainingSeconds); assertNull(stopped.estimatedArrivalEpochMs)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(stopped, f.controller.state.value)
        f.controller.startDemoDrive(); assertEquals(stopped, f.controller.state.value)
        f.controller.resumeDemoDrive(); runCurrent()
        assertEquals(30.0, requireNotNull(f.controller.state.value.demoDrive).speedKmh, 0.0)
        assertEquals(moving.position, f.controller.state.value.mapVehiclePosition)
        assertNotNull(f.controller.state.value.estimatedArrivalEpochMs)
        f.controller.resumeDemoDrive(); advanceTimeBy(1000); runCurrent()
        assertEquals(moving.traveledMeters + 30.0 / 3.6, requireNotNull(f.controller.state.value.demoDrive).traveledMeters, 0.000001)
        assertEquals(originalGps, f.controller.state.value.location)
        assertEquals(1, f.routes.calls)
    }
    @Test fun explicitPauseRemainsPausedWhenLeavingAndReturningToNavigation() = runTest {
        val f = fixture(); f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(1000); runCurrent()
        f.controller.stopDemoDrive()
        val paused = f.controller.state.value.demoDrive
        f.controller.setActive(false); advanceTimeBy(60_000); runCurrent()
        f.controller.resumeDemoDrive(); assertEquals(paused, f.controller.state.value.demoDrive)
        f.controller.setActive(true); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(paused, f.controller.state.value.demoDrive)
        assertEquals(paused?.position, f.controller.state.value.mapVehiclePosition)
        assertNull(f.controller.state.value.estimatedArrivalEpochMs)
        f.controller.resumeDemoDrive(); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(requireNotNull(paused).traveledMeters + 30.0 / 3.6, requireNotNull(f.controller.state.value.demoDrive).traveledMeters, 0.000001)
        assertEquals(1, f.routes.calls)
    }
    @Test fun exitClearsRunningPausedAndCompletedDemoAndReturnsTheMapToDeviceGps() = runTest {
        for (status in DemoDriveStatus.entries) {
            val f = fixture(); f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(2000); runCurrent()
            when (status) {
                DemoDriveStatus.RUNNING -> Unit
                DemoDriveStatus.PAUSED -> f.controller.stopDemoDrive()
                DemoDriveStatus.ARRIVED -> { advanceTimeBy(10_000); runCurrent() }
            }
            assertEquals(status, f.controller.state.value.demoDrive?.status)
            val route = f.controller.state.value.route; val gps = f.controller.state.value.location
            f.controller.exitDemoDrive()
            assertNull(f.controller.state.value.demoDrive); assertEquals(TripPhase.ROUTE_READY, f.controller.state.value.phase)
            assertSame(route, f.controller.state.value.route); assertEquals(gps.fix?.point, f.controller.state.value.mapVehiclePosition)
            assertNull(f.controller.state.value.remainingMeters); assertNull(f.controller.state.value.remainingSeconds)
            assertNull(f.controller.state.value.estimatedArrivalEpochMs)
            f.controller.resumeDemoDrive(); advanceTimeBy(1000); runCurrent()
            f.controller.setActive(false); f.controller.setActive(true); runCurrent()
            assertNull(f.controller.state.value.demoDrive); assertEquals(gps.fix?.point, f.controller.state.value.mapVehiclePosition)
            assertEquals(1, f.routes.calls)
            f.controller.close()
        }
    }
    @Test fun realGpsChangesAndLossDoNotMoveOrInvalidateTheDemoVehicle() = runTest {
        val f = fixture(); f.controller.startDemoDrive(); runCurrent()
        advanceTimeBy(1000); runCurrent()
        val before = f.controller.state.value.demoDrive
        val elsewhere = GeoPoint(52.1, 13.1)
        f.locations.fix(elsewhere, BASE + testScheduler.currentTime); runCurrent()
        assertEquals(elsewhere, f.controller.state.value.location.fix?.point)
        assertEquals(before, f.controller.state.value.demoDrive)
        f.locations.setPermission(LocationPermission.NONE); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(LocationPermission.NONE, f.controller.state.value.location.permission)
        assertTrue(requireNotNull(f.controller.state.value.demoDrive).traveledMeters > requireNotNull(before).traveledMeters)
        assertNull(f.controller.state.value.issue); assertNotNull(f.controller.state.value.route)
    }
    @Test fun leavingNavigationPausesAndReturningExcludesBackgroundTime() = runTest {
        val f = fixture(); f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(2000); runCurrent()
        f.controller.setActive(false)
        val paused = requireNotNull(f.controller.state.value.demoDrive)
        assertEquals(DemoDriveStatus.PAUSED, paused.status); assertFalse(f.locations.tracking)
        advanceTimeBy(120_000); runCurrent(); assertEquals(paused, f.controller.state.value.demoDrive)
        f.controller.setActive(true); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(paused.traveledMeters + 30.0 / 3.6, requireNotNull(f.controller.state.value.demoDrive).traveledMeters, 0.000001)
        assertEquals(TripPhase.TRIP_ACTIVE, f.controller.state.value.phase); assertEquals(1, f.routes.calls)
    }
    @Test fun duplicateStartsAndThemeChangesDoNotCreateMultipleSimulationLoops() = runTest {
        val f = fixture(); f.controller.startDemoDrive(); f.controller.startDemoDrive(); runCurrent()
        f.controller.setTheme(MapTheme.LIGHT)
        advanceTimeBy(1000); runCurrent()
        assertEquals(30.0 / 3.6, requireNotNull(f.controller.state.value.demoDrive).traveledMeters, 0.000001)
        assertEquals(MapTheme.LIGHT, f.controller.state.value.theme); assertEquals(1, f.routes.calls)
    }
    @Test fun destinationChangesClearingAndClosingCancelTheSimulation() = runTest {
        for (operation in listOf<(NavigationController) -> Unit>(
            { it.selectDestination(GeoPoint(52.002, 13.002)) }, { it.clearDestination() }, { it.endTrip() }, { it.close() })) {
            val f = fixture(); f.controller.startDemoDrive(); runCurrent()
            operation(f.controller); advanceTimeBy(5000); runCurrent()
            assertNull(f.controller.state.value.demoDrive); assertEquals(1, f.routes.calls)
        }
    }
    @Test fun realStartTripUsesGpsUntilTheUserExplicitlyChoosesDemo() = runTest {
        val f = fixture(); f.controller.startTrip(); runCurrent()
        assertEquals(TripPhase.TRIP_ACTIVE, f.controller.state.value.phase); assertNull(f.controller.state.value.demoDrive)
        f.controller.stopDemoDrive(); advanceTimeBy(5000); runCurrent()
        assertNull(f.controller.state.value.demoDrive)
        assertEquals(120.0, requireNotNull(f.controller.state.value.remainingMeters), 0.001)
        assertEquals(fixtureOrigin, f.controller.state.value.mapVehiclePosition)
        f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(DemoDriveStatus.RUNNING, f.controller.state.value.demoDrive?.status)
        assertEquals(30.0 / 3.6, requireNotNull(f.controller.state.value.demoDrive).traveledMeters, 0.000001)
        val demo = f.controller.state.value.demoDrive
        f.controller.startTrip(); assertEquals(demo, f.controller.state.value.demoDrive)
        f.controller.exitDemoDrive(); f.controller.startTrip()
        assertNull(f.controller.state.value.demoDrive)
        assertEquals(TripPhase.TRIP_ACTIVE, f.controller.state.value.phase)
        assertEquals(fixtureOrigin, f.controller.state.value.mapVehiclePosition)
        assertEquals(1, f.routes.calls)
    }
    @Test fun demoRequiresACalculatedRouteAndAnActiveNavigationScreen() = runTest {
        val f = fixture(); f.controller.setActive(false); f.controller.startDemoDrive(); runCurrent()
        assertNull(f.controller.state.value.demoDrive)
        f.controller.setActive(true); f.controller.clearDestination(); f.controller.startDemoDrive(); runCurrent()
        assertNull(f.controller.state.value.demoDrive); assertEquals(1, f.routes.calls)
    }
    companion object { private const val BASE = 1_000_000L; private const val WALL = 1_700_000_000_000L }
}
