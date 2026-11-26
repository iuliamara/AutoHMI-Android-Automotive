package com.example.autohmi.navigation

import com.example.autohmi.CompletedNavigationTrip
import org.junit.Assert.*
import org.junit.Test

class DemoTripStatisticsTest {
    private val start = GeoPoint(0.0, 0.0)
    private val end = GeoPoint(Math.toDegrees(10.0 / 6_371_008.8), 0.0)
    private val route = RoutePlan(start, Destination(GeoPoint(Math.toDegrees(50.0 / 6_371_008.8), 0.0), "Selected garden"),
        listOf(start, end), 100.0, 90.0)
    private val arrived = DemoDriveState(end, 10.0, 10.0, DemoDriveStatus.ARRIVED)

    @Test fun statsUseObservedMovementNotProviderEstimatesOrAHardcodedAverage() {
        val stats = DemoTripStatistics("session-a")
        stats.observeStep(0.0, 10.0, 10.0, 2.0)
        val trip = requireNotNull(stats.complete(route, arrived, 1234))
        assertEquals(1234L, trip.completedAtMillis)
        assertEquals(10.0, trip.distanceMeters, 0.0)
        assertEquals(2.0, trip.simulatedDurationSeconds, 0.0)
        assertEquals(18.0, trip.averageSpeedKmh, 0.000001)
        assertEquals("DEMO", trip.source); assertEquals("COMPLETED", trip.completionStatus)
        assertEquals("Selected garden", trip.destinationLabel)
        assertEquals(40.0, trip.destinationGapMeters, 0.001)
    }
    @Test fun pausedTimeAndTheUnusedPartOfTheFinalTickAreExcluded() {
        val stats = DemoTripStatistics()
        stats.observeStep(0.0, 8.0, 8.0, 1.0)
        stats.observeStep(8.0, 8.0, 8.0, 120.0)
        stats.observeStep(8.0, 16.0, 10.0, 1.0)
        val trip = requireNotNull(stats.complete(route, arrived, 1234))
        assertEquals(1.25, trip.simulatedDurationSeconds, 0.000001)
        assertEquals(28.8, trip.averageSpeedKmh, 0.000001)
    }
    @Test fun onlyArrivedAtTheActualDrivingEndpointWithRecordedMovementIsEligible() {
        assertNull(DemoTripStatistics().complete(route, arrived, 1234))
        val stats = DemoTripStatistics()
        stats.observeStep(0.0, 10.0, 10.0, 1.0)
        for (status in listOf(DemoDriveStatus.RUNNING, DemoDriveStatus.PAUSED)) assertNull(stats.complete(route, arrived.copy(status = status), 1234))
        assertNull(stats.complete(route, arrived.copy(traveledMeters = 9.0), 1234))
        assertNull(stats.complete(route, arrived.copy(position = route.destination.point), 1234))
        assertNotNull(stats.complete(route, arrived, 1234))
    }
    @Test fun repeatedCompletionEventsProduceOnlyOneStableRecord() {
        val stats = DemoTripStatistics("stable-id")
        stats.observeStep(0.0, 10.0, 10.0, 1.0)
        assertEquals("stable-id", stats.complete(route, arrived, 1234)?.completionId)
        assertNull(stats.complete(route, arrived, 9999))
    }
    @Test fun entityRejectsIncompleteOrInventedStatistics() {
        val trip = CompletedNavigationTrip("id", 1234, 2.0, 10.0, 18.0)
        for (invalid in listOf<() -> CompletedNavigationTrip>(
            { trip.copy(source = "GPS") }, { trip.copy(completionStatus = "PAUSED") },
            { trip.copy(simulatedDurationSeconds = 0.0) }, { trip.copy(distanceMeters = Double.NaN) },
            { trip.copy(averageSpeedKmh = 30.0) })) assertTrue(runCatching(invalid).isFailure)
    }
}
