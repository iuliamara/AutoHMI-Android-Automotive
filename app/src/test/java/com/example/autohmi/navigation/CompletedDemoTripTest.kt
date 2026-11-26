package com.example.autohmi.navigation

import com.example.autohmi.CompletedNavigationTrip
import com.example.autohmi.CompletedTripRecorder
import com.example.autohmi.CompletedTripStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompletedDemoTripTest {
    private class Store : CompletedTripStore {
        val rows = linkedMapOf<String, CompletedNavigationTrip>()
        val requests = mutableListOf<CompletedNavigationTrip>()
        var attempts = 0; var failures = 0; var gate: CompletableDeferred<Unit>? = null
        override suspend fun saveCompletedTrip(trip: CompletedNavigationTrip) {
            attempts++; requests += trip; gate?.await()
            if (failures > 0) { failures--; throw IOException("Synthetic storage failure") }
            rows.putIfAbsent(trip.completionId, trip)
        }
    }
    private data class Fixture(val controller: NavigationController, val store: Store, val locations: FakeLocations,
        val routes: FakeRoutes, val viewScope: CoroutineScope, val endpoint: GeoPoint)
    private fun TestScope.fixture(gap: Double = 100.0): Fixture {
        fun north(meters: Double) = GeoPoint(fixtureOrigin.latitude + Math.toDegrees(meters / 6_371_008.8), fixtureOrigin.longitude)
        val locations = FakeLocations(); val routes = FakeRoutes(); val store = Store()
        val viewScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        routes.handler = { origin, target -> RouteResult.Success(RoutePlan(origin, target, listOf(origin, north(95.0)), 120.0, 60.0)) }
        val controller = NavigationController(locations, routes, viewScope, { BASE + testScheduler.currentTime }, { WALL + testScheduler.currentTime },
            CompletedTripRecorder(store, backgroundScope))
        controller.setActive(true); locations.fix(fixtureOrigin, BASE + testScheduler.currentTime); runCurrent()
        controller.selectDestination(north(95.0 + gap), "Selected destination"); controller.calculateRoute(); runCurrent()
        return Fixture(controller, store, locations, routes, viewScope, north(95.0))
    }
    @Test fun completionAutomaticallySavesActualMotionTimeDistanceAverageAndGapOnce() = runTest {
        val f = fixture(); f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(2000); runCurrent()
        f.controller.stopDemoDrive(); advanceTimeBy(60_000); runCurrent(); assertTrue(f.store.rows.isEmpty())
        f.controller.resumeDemoDrive(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        val trip = f.store.rows.values.single()
        assertEquals(WALL + testScheduler.currentTime, trip.completedAtMillis)
        assertEquals(95.0, trip.distanceMeters, 0.001)
        assertEquals(11.4, trip.simulatedDurationSeconds, 0.000001)
        assertEquals(30.0, trip.averageSpeedKmh, 0.000001)
        assertEquals(100.0, trip.destinationGapMeters, 0.001)
        assertEquals("Selected destination", trip.destinationLabel)
        assertEquals(CompletionSaveStatus.SAVED, f.controller.state.value.completionSaveStatus)
        f.controller.setActive(false); f.controller.setActive(true); f.controller.setTheme(MapTheme.LIGHT)
        f.controller.overview(); f.controller.retryCompletedTripSave(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.store.rows.size); assertEquals(1, f.store.attempts); assertEquals(1, f.routes.calls)
        f.viewScope.cancel()
    }
    @Test fun pausedCancelledAbandonedAndChangedDestinationsAreNotCompleted() = runTest {
        for (operation in listOf<(NavigationController) -> Unit>(
            { it.stopDemoDrive() }, { it.exitDemoDrive() }, { it.endTrip() }, { it.clearDestination() },
            { it.selectDestination(GeoPoint(52.002, 13.002)) }, { it.close() })) {
            val f = fixture(); f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(2000); runCurrent()
            operation(f.controller); advanceTimeBy(60_000); runCurrent()
            assertTrue(f.store.rows.isEmpty()); assertEquals(0, f.store.attempts)
            f.controller.close(); f.viewScope.cancel()
        }
    }
    @Test fun realGpsArrivalDoesNotCreateADemoHistoryRecord() = runTest {
        val f = fixture(); f.controller.startTrip(); runCurrent()
        repeat(3) { advanceTimeBy(2000); f.locations.fix(f.endpoint, BASE + testScheduler.currentTime); runCurrent() }
        assertEquals(TripPhase.ARRIVED, f.controller.state.value.phase)
        assertTrue(f.store.rows.isEmpty()); assertEquals(0, f.store.attempts)
        f.viewScope.cancel()
    }
    @Test fun appOwnedWriteSurvivesExitAndViewModelScopeCancellation() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>(); f.store.gate = gate
        f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(12_000); runCurrent()
        assertEquals(CompletionSaveStatus.SAVING, f.controller.state.value.completionSaveStatus)
        f.controller.exitDemoDrive(); f.controller.close(); f.viewScope.cancel()
        gate.complete(Unit); runCurrent()
        assertEquals(1, f.store.rows.size)
        assertEquals(CompletionSaveStatus.NONE, f.controller.state.value.completionSaveStatus)
    }
    @Test fun failedSaveCanRetryWithTheSameIdWithoutChangingCompletionStatistics() = runTest {
        val f = fixture(); f.store.failures = 1
        f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(12_000); runCurrent()
        assertEquals(CompletionSaveStatus.FAILED, f.controller.state.value.completionSaveStatus)
        assertTrue(f.store.rows.isEmpty())
        advanceTimeBy(5000); f.controller.retryCompletedTripSave(); runCurrent()
        val trip = f.store.rows.values.single()
        assertEquals(WALL + 12_000, trip.completedAtMillis)
        assertEquals(11.4, trip.simulatedDurationSeconds, 0.000001)
        assertEquals(CompletionSaveStatus.SAVED, f.controller.state.value.completionSaveStatus)
        assertEquals(2, f.store.attempts)
        assertEquals(1, f.store.requests.map { it.completionId }.distinct().size)
        assertEquals(f.store.requests.first(), trip)
        f.viewScope.cancel()
    }
    @Test fun separateCompletedDrivesOfTheSameRouteHaveDistinctIds() = runTest {
        val f = fixture()
        repeat(2) {
            f.controller.startDemoDrive(); runCurrent(); advanceTimeBy(12_000); runCurrent()
            f.controller.exitDemoDrive()
        }
        assertEquals(2, f.store.rows.size); assertEquals(2, f.store.rows.keys.distinct().size)
        assertEquals(1, f.routes.calls)
        f.viewScope.cancel()
    }
    companion object { private const val BASE = 1_000_000L; private const val WALL = 1_700_000_000_000L }
}
