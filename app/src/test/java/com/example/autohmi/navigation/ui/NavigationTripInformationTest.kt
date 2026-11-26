package com.example.autohmi.navigation.ui

import com.example.autohmi.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavigationTripInformationTest {
    private val start = GeoPoint(52.52, 13.405)
    private val end = GeoPoint(52.521, 13.405)
    private val destination = Destination(end)
    private fun gps() = NavigationState(route = RoutePlan(start, destination, listOf(start, end), 120.0, 60.0),
        phase = TripPhase.TRIP_ACTIVE, remainingMeters = 80.0, remainingSeconds = 40.0, estimatedArrivalEpochMs = 1_700_000_000_000L)

    @Test fun noRouteHasNoSummary() { assertNull(NavigationState().tripInformation()) }
    @Test fun gpsUsesExistingDistanceAndArrival() {
        val state = gps()
        val info = requireNotNull(state.tripInformation())
        assertEquals("GPS · Navigating", info.status)
        assertEquals(state.remainingMeters, info.remainingMeters)
        assertEquals(state.estimatedArrivalEpochMs, info.arrivalEpochMs)
    }
    @Test fun demoMovementReflectsUpdatedStateWithoutCalculatingNewEstimates() {
        val initial = gps().copy(demoDrive = DemoDriveState(start, 40.0, 120.0))
        val moved = initial.copy(remainingMeters = 50.0, estimatedArrivalEpochMs = 1_700_000_010_000L,
            demoDrive = initial.demoDrive?.copy(traveledMeters = 70.0))
        assertEquals("DEMO · Driving", initial.tripInformation()?.status)
        assertEquals(50.0, requireNotNull(moved.tripInformation()?.remainingMeters), 0.0)
        assertEquals(moved.estimatedArrivalEpochMs, moved.tripInformation()?.arrivalEpochMs)
    }
    @Test fun pausedDemoRetainsDistanceAndHidesOldEta() {
        val info = requireNotNull(gps().copy(demoDrive = DemoDriveState(start, 40.0, 120.0, DemoDriveStatus.PAUSED)).tripInformation())
        assertTrue(info.paused)
        assertEquals("DEMO · Paused", info.status)
        assertEquals(80.0, requireNotNull(info.remainingMeters), 0.0)
        assertNull(info.arrivalEpochMs)
    }
    @Test fun demoArrivalShowsZeroAndSeparateSelectedDestination() {
        val state = gps()
        val plan = requireNotNull(state.route).copy(destination = Destination(GeoPoint(52.522, 13.405)))
        val info = requireNotNull(state.copy(route = plan,
            demoDrive = DemoDriveState(end, 120.0, 120.0, DemoDriveStatus.ARRIVED)).tripInformation())
        assertTrue(info.arrived)
        assertEquals("DEMO · Arrived", info.status)
        assertEquals(0.0, requireNotNull(info.remainingMeters), 0.0)
        assertEquals(plan.destinationGapMeters, requireNotNull(info.destinationGapMeters), 0.0)
        assertNull(info.arrivalEpochMs)
    }
    @Test fun gpsArrivalSuppressesRemainingEta() {
        val info = requireNotNull(gps().copy(phase = TripPhase.ARRIVED).tripInformation())
        assertEquals("GPS · Arrived", info.status)
        assertEquals(0.0, requireNotNull(info.remainingMeters), 0.0)
        assertNull(info.arrivalEpochMs)
    }
    @Test fun unavailableProgressNeverInventsDistanceOrEta() {
        val info = requireNotNull(gps().copy(remainingMeters = null, estimatedArrivalEpochMs = null,
            issue = NavigationIssue.STALE_LOCATION).tripInformation())
        assertNull(info.remainingMeters)
        assertNull(info.arrivalEpochMs)
        assertEquals("GPS · Progress unavailable", info.status)
    }
    @Test fun readyRouteShowsItsExistingTotalUntilNavigationBegins() {
        val info = requireNotNull(gps().copy(phase = TripPhase.ROUTE_READY, remainingMeters = null,
            estimatedArrivalEpochMs = null).tripInformation())
        assertEquals(120.0, requireNotNull(info.remainingMeters), 0.0)
        assertEquals("GPS · Route ready", info.status)
        assertNull(info.arrivalEpochMs)
    }
}
