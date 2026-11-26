package com.example.autohmi.navigation

import com.example.autohmi.CompletedNavigationTrip
import java.util.UUID

/** Observes movement already calculated by the engine; never advances a vehicle or a clock. */
internal class DemoTripStatistics(val completionId: String = UUID.randomUUID().toString()) {
    private var motionSeconds = 0.0
    private var completionIssued = false

    fun observeStep(previousMeters: Double, requestedMeters: Double, actualMeters: Double, intervalSeconds: Double) {
        val requestedAdvance = requestedMeters - previousMeters
        val actualAdvance = actualMeters - previousMeters
        if (completionIssued || intervalSeconds <= 0 || requestedAdvance <= 0 || actualAdvance <= 0) return
        // The final tick can overshoot. Count only the fraction actually driven before stopping.
        motionSeconds += intervalSeconds * (actualAdvance / requestedAdvance).coerceIn(0.0, 1.0)
    }

    fun complete(route: RoutePlan, demo: DemoDriveState, completedAtMillis: Long): CompletedNavigationTrip? {
        if (completionIssued || demo.status != DemoDriveStatus.ARRIVED || demo.progressFraction < 1.0 ||
            demo.traveledMeters <= 0 || motionSeconds <= 0 ||
            GeoMath.distance(demo.position, route.drivingEndpoint) > RoutePlan.ENDPOINT_TOLERANCE_METERS) return null
        val trip = CompletedNavigationTrip(completionId, completedAtMillis, motionSeconds,
            demo.traveledMeters, demo.traveledMeters / motionSeconds * 3.6,
            destinationLabel = route.destination.name.trim().take(120).takeIf { it.isNotEmpty() },
            destinationGapMeters = route.destinationGapMeters)
        completionIssued = true
        return trip
    }
}
