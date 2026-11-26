package com.example.autohmi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.autohmi.CompletedNavigationTrip
import com.example.autohmi.navigation.RoutePlan
import com.example.autohmi.navigation.ui.distanceLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CompletedNavigationTripCard(trip: CompletedNavigationTrip) {
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(trip.completedAtMillis, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(trip.completedAtMillis))
    }
    Card(Modifier.fillMaxWidth().testTag("completed_trip_${trip.completionId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Text("${trip.source} · ${trip.completionStatus}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            Text(trip.destinationLabel ?: "Completed navigation trip", style = MaterialTheme.typography.titleMedium)
            Text("Driving endpoint reached", style = MaterialTheme.typography.bodyMedium)
            Text("${distanceLabel(trip.distanceMeters)} driven", style = MaterialTheme.typography.titleLarge)
            SupportingText("${simulatedDurationLabel(trip.simulatedDurationSeconds, locale)} simulated driving time")
            SupportingText(String.format(locale, "%.1f km/h average", trip.averageSpeedKmh))
            if (trip.destinationGapMeters > RoutePlan.DESTINATION_GAP_WARNING_METERS) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Text("Selected destination approximately ${trip.destinationGapMeters.roundToInt()} m from the driving endpoint. Gap not driven; access beyond the endpoint is unknown.",
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            SupportingText("Completed $date")
        }
    }
}

internal fun simulatedDurationLabel(seconds: Double, locale: Locale): String = when {
    seconds < 60 -> String.format(locale, "%.1f s", seconds)
    seconds < 3600 -> "${seconds.toLong() / 60} min ${seconds.toLong() % 60} s"
    else -> "${seconds.toLong() / 3600} h ${(seconds.toLong() % 3600) / 60} min"
}
