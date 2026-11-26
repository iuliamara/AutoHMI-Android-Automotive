package com.example.autohmi.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autohmi.navigation.*
import java.text.DateFormat
import java.util.Date

/** Presentation only: distances and arrival estimates remain owned by NavigationController. */
internal data class NavigationTripInformationState(
    val status: String,
    val remainingMeters: Double?,
    val arrivalEpochMs: Long?,
    val paused: Boolean,
    val arrived: Boolean,
    val destinationGapMeters: Double?
)

internal fun NavigationState.tripInformation(): NavigationTripInformationState? {
    val plan = route ?: return null
    val demo = demoDrive
    val arrived = demo?.status == DemoDriveStatus.ARRIVED || (demo == null && phase == TripPhase.ARRIVED)
    val paused = demo?.status == DemoDriveStatus.PAUSED
    val status = if (demo != null) when (demo.status) {
        DemoDriveStatus.RUNNING -> "DEMO · Driving"
        DemoDriveStatus.PAUSED -> "DEMO · Paused"
        DemoDriveStatus.ARRIVED -> "DEMO · Arrived"
    } else when (phase) {
        TripPhase.ARRIVED -> "GPS · Arrived"
        TripPhase.TRIP_ACTIVE -> if (issue == null) "GPS · Navigating" else "GPS · Progress unavailable"
        TripPhase.ROUTE_READY -> "GPS · Route ready"
        TripPhase.CALCULATING -> "GPS · Calculating route"
        TripPhase.ERROR -> "GPS · Route unavailable"
        else -> "GPS · Route planned"
    }
    return NavigationTripInformationState(status,
        if (arrived) 0.0 else remainingMeters ?: plan.distanceMeters.takeIf { phase == TripPhase.ROUTE_READY },
        estimatedArrivalEpochMs.takeUnless { paused || arrived }, paused, arrived,
        plan.destinationGapMeters.takeIf { arrived && plan.hasDestinationGap })
}

@Composable
internal fun NavigationTripInformation(state: NavigationState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val info = state.tripInformation() ?: return
    Surface(modifier.testTag(if (compact) "collapsed_navigation_info" else "expanded_navigation_info"),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (compact) 4.dp else 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.status, Modifier.fillMaxWidth().testTag("trip_info_status"), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(info.remainingMeters?.let { "${distanceLabel(it)} remaining" } ?: "Distance unavailable",
                Modifier.fillMaxWidth().testTag("trip_info_distance"), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(when {
                info.arrived -> "Driving endpoint reached"
                info.paused -> "Resume DEMO for ETA"
                info.arrivalEpochMs != null -> "ETA ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(info.arrivalEpochMs))}"
                else -> "ETA unavailable"
            }, Modifier.fillMaxWidth().testTag("trip_info_eta"), style = MaterialTheme.typography.bodyLarge)
            if (!compact && !info.arrived && !info.paused) state.remainingSeconds?.let {
                Text("${durationLabel(it)} estimated driving time", style = MaterialTheme.typography.bodySmall)
            }
            if (info.arrived) info.destinationGapMeters?.let {
                Text("Selected destination ${distanceLabel(it)} away · gap excluded", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
