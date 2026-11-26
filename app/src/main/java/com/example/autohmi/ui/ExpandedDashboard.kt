package com.example.autohmi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autohmi.*
import com.example.autohmi.R

/** Shared readouts/actions, composed horizontally. Only secondary trip history scrolls normally. */
@Composable
internal fun ExpandedDashboard(
    vehicle: VehicleStatus, history: HistoryState, ready: Boolean, settingsError: Boolean, saving: Boolean,
    onAccelerate: () -> Unit, onBrake: () -> Unit, onToggle: () -> Unit, onMode: (DrivingMode) -> Unit,
    onReset: () -> Unit, onSave: () -> Unit, onHistory: () -> Unit, onNavigation: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp).testTag("dashboard_expanded")) {
        val availableWidth = maxWidth
        val short = maxHeight < 450.dp && maxWidth >= 820.dp
        val fontScale = LocalDensity.current.fontScale
        val needsPrimaryScroll = fontScale > 1.5f ||
            (short && (maxHeight < 280.dp || (fontScale > 1.15f && maxHeight < 330.dp))) ||
            (!short && (maxHeight < 450.dp || (fontScale > 1.15f && maxHeight < 510.dp)))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(if (short) 2.1f else 1.45f).fillMaxHeight()
                .then(if (needsPrimaryScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 12.dp)) {
                Text(stringResource(R.string.vehicle_overview), style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1.2f)) { SpeedCard(vehicle, compact = true, short = short || availableWidth < 1000.dp, largeHero = availableWidth >= 1100.dp) }
                    Column(Modifier.weight(if (short) 0.9f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(stringResource(R.string.battery), stringResource(R.string.battery_percent, vehicle.batteryPercent),
                            if (short) null else stringResource(R.string.battery_energy, vehicle.batteryEnergyKwh), "vehicle_battery")
                        Metric(stringResource(R.string.estimated_range), stringResource(R.string.range_value, vehicle.rangeKm),
                            if (short) null else stringResource(R.string.range_note), "vehicle_range")
                    }
                    if (short) Column(Modifier.weight(0.95f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.driving_mode), style = MaterialTheme.typography.titleMedium)
                        DrivingMode.entries.forEach { mode -> FilterChip(selected = ready && vehicle.drivingMode == mode,
                            enabled = ready, onClick = { onMode(mode) }, label = { Text(mode.name) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) }
                    }
                }
                if (!short) ModeSelector(vehicle, ready, settingsError, onMode, horizontal = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onToggle, enabled = vehicle.batteryEnergyKwh > 0,
                        modifier = Modifier.weight(1.2f).heightIn(min = 64.dp)) {
                        Text(stringResource(if (vehicle.isSimulationRunning) R.string.pause_simulation else R.string.start_simulation))
                    }
                    OutlinedButton(onClick = onBrake, enabled = vehicle.speedKmh > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)) { Text(stringResource(R.string.brake)) }
                    OutlinedButton(onClick = onAccelerate, enabled = vehicle.speedKmh < MAX_SPEED_KMH && vehicle.batteryEnergyKwh > 0,
                        modifier = Modifier.weight(1.2f).heightIn(min = 64.dp)) { Text(stringResource(R.string.accelerate)) }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("dashboard_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                item {
                    Text("Trip & cabin", style = MaterialTheme.typography.headlineSmall)
                    SupportingText("Vehicle simulation · ${SIMULATED_SECONDS_PER_TICK.toInt()}× time scale", Modifier.padding(top = 4.dp))
                    if (short) {
                        SupportingText(stringResource(R.string.battery_energy, vehicle.batteryEnergyKwh))
                        SupportingText(stringResource(R.string.mode_consumption, vehicle.consumptionKwhPerKm * 100))
                        if (!ready) SupportingText(stringResource(if (settingsError) R.string.mode_error else R.string.mode_loading))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { Metric(stringResource(R.string.trip_distance), stringResource(R.string.distance_value, vehicle.distanceKm), null, "vehicle_trip") }
                        Box(Modifier.weight(1f)) { Metric(stringResource(R.string.temperature), stringResource(R.string.temperature_value, vehicle.cabinTemperatureC, vehicle.outsideTemperatureC), null, "vehicle_temperature") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onReset, enabled = vehicle.distanceKm > 0,
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp)) { Text(stringResource(R.string.reset_trip)) }
                        Button(onClick = onSave, enabled = vehicle.distanceKm > 0 && !saving,
                            modifier = Modifier.weight(1f).heightIn(min = 64.dp)) { Text(stringResource(if (saving) R.string.saving else R.string.save_snapshot)) }
                    }
                    if (vehicle.distanceKm == 0.0) SupportingText(stringResource(R.string.save_hint), Modifier.padding(top = 8.dp))
                }
                item { OutlinedButton(onClick = onNavigation, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Open navigation") } }
                item {
                    Row {
                        Text(stringResource(R.string.recent_snapshots), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onHistory) { Text(stringResource(R.string.view_all)) }
                    }
                    HistoryNotice(history)
                }
                items(history.snapshots.take(3), key = { it.id }) { SnapshotCard(it) }
                item { SupportingText(stringResource(R.string.simulation_footer)) }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, note: String?, tag: String) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SupportingText(label)
            Text(value, style = MaterialTheme.typography.titleLarge)
            note?.let { SupportingText(it) }
        }
    }
}
