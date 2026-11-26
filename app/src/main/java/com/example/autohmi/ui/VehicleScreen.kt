package com.example.autohmi.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.autohmi.DrivingMode
import com.example.autohmi.HistoryState
import com.example.autohmi.MAX_SPEED_KMH
import com.example.autohmi.R
import com.example.autohmi.TripSnapshot
import com.example.autohmi.VehicleMessage
import com.example.autohmi.VehicleStatus
import com.example.autohmi.VehicleViewModel
import com.example.autohmi.music.MusicViewModel
import com.example.autohmi.music.MusicUiState
import com.example.autohmi.music.ui.MusicScreen
import com.example.autohmi.music.ui.CompactMusicPlayer
import com.example.autohmi.music.ui.NavigationMusicPlayer
import com.example.autohmi.music.web.SpotifyWebState
import java.text.DateFormat
import java.util.Date

private enum class DashboardTab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    DASHBOARD(R.string.dashboard, R.drawable.ic_dashboard),
    MUSIC(R.string.music, R.drawable.ic_music),
    NAVIGATION(R.string.navigation, R.drawable.ic_navigation),
    HISTORY(R.string.history, R.drawable.ic_history),
    SETTINGS(R.string.settings, R.drawable.ic_settings)
}

@Composable
fun VehicleScreen(viewModel: VehicleViewModel, musicViewModel: MusicViewModel, openMusicRequest: Int = 0, navigationContent: @Composable () -> Unit = {}) {
    val vehicle by viewModel.vehicleStatus.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val settingsReady by viewModel.settingsReady.collectAsStateWithLifecycle()
    val settingsError by viewModel.settingsError.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val music by musicViewModel.state.collectAsStateWithLifecycle()
    val spotifyWeb by musicViewModel.spotify.web.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(viewModel, resources, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect { message ->
                val text = when (message) {
                    VehicleMessage.SNAPSHOT_SAVED -> R.string.snapshot_saved
                    VehicleMessage.SNAPSHOT_DELETED -> R.string.snapshot_deleted
                    VehicleMessage.SAVE_FAILED -> R.string.snapshot_save_failed
                    VehicleMessage.DELETE_FAILED -> R.string.snapshot_delete_failed
                    VehicleMessage.SETTINGS_FAILED -> R.string.settings_save_failed
                }
                snackbar.showSnackbar(resources.getString(text))
            }
        }
    }

    VehicleApp(
        vehicle = vehicle,
        history = history,
        settingsReady = settingsReady,
        settingsError = settingsError,
        isSaving = isSaving,
        snackbar = snackbar,
        onAccelerate = viewModel::accelerate,
        onBrake = viewModel::brake,
        onToggleSimulation = viewModel::toggleSimulation,
        onDrivingModeChange = viewModel::changeDrivingMode,
        onResetTrip = viewModel::resetTrip,
        onSaveSnapshot = viewModel::saveTripSnapshot,
        onDeleteSnapshot = viewModel::deleteSnapshot,
        musicState = music,
        onMusicToggle = {
            if (music.player.isPlaying) musicViewModel.session.pause() else musicViewModel.session.play()
        },
        musicContent = {
            MusicScreen(music, musicViewModel)
        },
        openMusicRequest = openMusicRequest,
        navigationContent = navigationContent,
        onMusicNext = musicViewModel.session::next,
        onMusicPrevious = musicViewModel.session::previous,
        spotifyWebState = spotifyWeb
    )
}

@Composable
fun VehicleApp(
    vehicle: VehicleStatus,
    history: HistoryState,
    settingsReady: Boolean,
    settingsError: Boolean,
    isSaving: Boolean,
    snackbar: SnackbarHostState,
    onAccelerate: () -> Unit,
    onBrake: () -> Unit,
    onToggleSimulation: () -> Unit,
    onDrivingModeChange: (DrivingMode) -> Unit,
    onResetTrip: () -> Unit,
    onSaveSnapshot: () -> Unit,
    onDeleteSnapshot: (Long) -> Unit,
    musicState: MusicUiState = MusicUiState(),
    onMusicToggle: () -> Unit = {},
    musicContent: @Composable () -> Unit = {},
    openMusicRequest: Int = 0,
    navigationContent: @Composable () -> Unit = {},
    onMusicNext: () -> Unit = {},
    onMusicPrevious: () -> Unit = {},
    spotifyWebState: SpotifyWebState = SpotifyWebState()
) {
    var tab by rememberSaveable { mutableStateOf(DashboardTab.DASHBOARD) }
    var navigationMusicCollapsed by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(openMusicRequest) {
        if (openMusicRequest > 0) tab = DashboardTab.MUSIC
    }

    BackHandler(enabled = tab != DashboardTab.DASHBOARD) { tab = DashboardTab.DASHBOARD }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
    val expanded = usesExpandedLayout(maxWidth.value, maxHeight.value)
    val shortWindow = maxHeight < 480.dp
    CompositionLocalProvider(LocalExpandedWindow provides expanded) {
    val type = MaterialTheme.typography
    val roomy = expanded && maxWidth >= 1200.dp && maxHeight >= 560.dp
    MaterialTheme(typography = if (roomy) type.copy(
        headlineSmall = type.headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp),
        titleLarge = type.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp),
        titleMedium = type.titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
        titleSmall = type.titleSmall.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyLarge = type.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = type.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = type.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = type.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        labelMedium = type.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = type.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp)
    ) else type) {
    Row(Modifier.fillMaxSize()) {
    if (expanded) NavigationRail(
        modifier = Modifier.width(104.dp).fillMaxHeight().testTag("navigation_rail"),
        windowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        header = { Text("AutoHMI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = if (shortWindow) 8.dp else 16.dp)) }
    ) {
        DashboardTab.entries.forEach { destination ->
            NavigationRailItem(selected = tab == destination, onClick = { tab = destination },
                modifier = Modifier.height(if (shortWindow) 64.dp else 72.dp),
                icon = { Icon(painterResource(destination.icon), null) },
                label = { Text(stringResource(destination.label)) })
        }
        Spacer(Modifier.weight(1f))
        Text("Prototype", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = if (shortWindow) 8.dp else 16.dp))
    }
    Scaffold(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.testTag("app_bottom_bar")) {
                if (tab == DashboardTab.NAVIGATION) {
                    NavigationMusicPlayer(musicState, spotifyWebState, navigationMusicCollapsed,
                        { navigationMusicCollapsed = it }, { tab = DashboardTab.MUSIC }, onMusicToggle, onMusicNext)
                } else if (tab != DashboardTab.MUSIC && (expanded || musicState.player.track != null)) {
                    CompactMusicPlayer(musicState, { tab = DashboardTab.MUSIC }, onMusicToggle,
                        onMusicNext, onMusicPrevious, expanded)
                }
                if (!expanded) NavigationBar(containerColor = MaterialTheme.colorScheme.surface,
                    windowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.testTag("bottom_navigation")) {
                    DashboardTab.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = tab == destination,
                            onClick = { tab = destination },
                            icon = {
                                Icon(painterResource(destination.icon), contentDescription = null)
                            },
                            label = { Text(stringResource(destination.label), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).testTag("app_content"), contentAlignment = Alignment.TopCenter) {
            when (tab) {
                DashboardTab.DASHBOARD -> Dashboard(
                    vehicle, history, settingsReady, settingsError, isSaving,
                    onAccelerate, onBrake, onToggleSimulation, onDrivingModeChange,
                    { confirmReset = true }, onSaveSnapshot, { tab = DashboardTab.HISTORY }, expanded,
                    { tab = DashboardTab.NAVIGATION }
                )
                DashboardTab.HISTORY -> History(history, expanded) { deleteId = it }
                DashboardTab.MUSIC -> musicContent()
                DashboardTab.NAVIGATION -> Box(Modifier.fillMaxSize().clipToBounds().testTag("navigation_content_safe_area")) {
                    navigationContent()
                }
                DashboardTab.SETTINGS -> Settings(
                    vehicle, settingsReady, settingsError, onDrivingModeChange, expanded
                )
            }
        }
    }
    }
    }
    }
    }
    }

    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.reset_title),
            description = stringResource(R.string.reset_description),
            action = stringResource(R.string.reset_trip),
            onDismiss = { confirmReset = false },
            onConfirm = { onResetTrip(); confirmReset = false }
        )
    }
    deleteId?.let { id ->
        ConfirmationDialog(
            title = stringResource(R.string.delete_title, id),
            description = stringResource(R.string.delete_description),
            action = stringResource(R.string.delete_snapshot),
            onDismiss = { deleteId = null },
            onConfirm = { onDeleteSnapshot(id); deleteId = null }
        )
    }
}

private val ContentPadding = PaddingValues(20.dp)
private val ContentSpacing = Arrangement.spacedBy(16.dp)

@Composable
private fun Dashboard(
    vehicle: VehicleStatus,
    history: HistoryState,
    settingsReady: Boolean,
    settingsError: Boolean,
    isSaving: Boolean,
    onAccelerate: () -> Unit,
    onBrake: () -> Unit,
    onToggleSimulation: () -> Unit,
    onDrivingModeChange: (DrivingMode) -> Unit,
    onResetTrip: () -> Unit,
    onSaveSnapshot: () -> Unit,
    onViewHistory: () -> Unit,
    expanded: Boolean = false,
    onOpenNavigation: () -> Unit = {}
) {
    if (expanded) {
        ExpandedDashboard(vehicle, history, settingsReady, settingsError, isSaving, onAccelerate, onBrake,
            onToggleSimulation, onDrivingModeChange, onResetTrip, onSaveSnapshot, onViewHistory, onOpenNavigation)
        return
    }
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("dashboard_list"),
        contentPadding = ContentPadding,
        verticalArrangement = ContentSpacing
    ) {
        item { ScreenHeading(R.string.vehicle_overview, R.string.overview_description) }
        item {
            SpeedCard(vehicle)
        }
        item {
            Button(
                onClick = onToggleSimulation,
                enabled = vehicle.batteryEnergyKwh > 0.0,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text(stringResource(if (vehicle.isSimulationRunning) R.string.pause_simulation else R.string.start_simulation))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onBrake,
                    enabled = vehicle.speedKmh > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.brake)) }
                OutlinedButton(
                    onClick = onAccelerate,
                    enabled = vehicle.speedKmh < MAX_SPEED_KMH && vehicle.batteryEnergyKwh > 0.0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.accelerate)) }
            }
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(
                    stringResource(R.string.battery),
                    stringResource(R.string.battery_percent, vehicle.batteryPercent),
                    stringResource(R.string.battery_energy, vehicle.batteryEnergyKwh),
                    Modifier.weight(1f)
                )
                StatusCard(
                    stringResource(R.string.estimated_range),
                    stringResource(R.string.range_value, vehicle.rangeKm),
                    stringResource(R.string.range_note),
                    Modifier.weight(1f)
                )
            }
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(
                    stringResource(R.string.trip_distance),
                    stringResource(R.string.distance_value, vehicle.distanceKm),
                    null,
                    Modifier.weight(1f)
                )
                StatusCard(
                    stringResource(R.string.temperature),
                    stringResource(R.string.temperature_value, vehicle.cabinTemperatureC, vehicle.outsideTemperatureC),
                    stringResource(R.string.temperature_note),
                    Modifier.weight(1f)
                )
            }
        }
        item { ModeSelector(vehicle, settingsReady, settingsError, onDrivingModeChange) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onResetTrip,
                    enabled = vehicle.distanceKm > 0.0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.reset_trip)) }
                Button(
                    onClick = onSaveSnapshot,
                    enabled = vehicle.distanceKm > 0.0 && !isSaving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(stringResource(if (isSaving) R.string.saving else R.string.save_snapshot)) }
            }
            if (vehicle.distanceKm == 0.0) {
                SupportingText(stringResource(R.string.save_hint), Modifier.padding(top = 8.dp))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recent_snapshots), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onViewHistory) { Text(stringResource(R.string.view_all)) }
            }
            HistoryNotice(history)
        }
        items(history.snapshots.take(3), key = { it.id }) { snapshot -> SnapshotCard(snapshot) }
        item { SupportingText(stringResource(R.string.simulation_footer)) }
    }
}

@Composable
internal fun SpeedCard(vehicle: VehicleStatus, compact: Boolean = false, short: Boolean = false, largeHero: Boolean = false) {
    val speedDescription = stringResource(R.string.speed_accessibility, vehicle.speedKmh)
    val status = when {
        vehicle.batteryEnergyKwh == 0.0 -> R.string.battery_empty
        vehicle.isSimulationRunning -> R.string.running
        else -> R.string.paused
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("vehicle_speed")
    ) {
        Column(Modifier.padding(if (short) 12.dp else if (compact) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else if (compact) 8.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.speed), color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(status), fontWeight = FontWeight.SemiBold)
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = speedDescription }
            ) {
                Text(
                    stringResource(R.string.speed_value, vehicle.speedKmh),
                    fontSize = if (short) 56.sp else if (largeHero) 92.sp else 68.sp,
                    lineHeight = if (short) 64.sp else if (largeHero) 100.sp else 76.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace
                )
                Text(stringResource(R.string.speed_unit), Modifier.padding(bottom = 10.dp), style = if (short) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge)
            }
            LinearProgressIndicator(
                progress = { vehicle.speedKmh.toFloat() / MAX_SPEED_KMH },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(vehicle.drivingMode.name, style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.time_scale), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxHeight()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SupportingText(label)
            Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            note?.let { SupportingText(it) }
        }
    }
}

@Composable
internal fun ModeSelector(
    vehicle: VehicleStatus,
    ready: Boolean,
    hasError: Boolean,
    onSelect: (DrivingMode) -> Unit,
    horizontal: Boolean = false
) {
    val modeChip: @Composable (DrivingMode, Modifier) -> Unit = { mode, chipModifier ->
        FilterChip(
            selected = vehicle.drivingMode == mode && ready,
            enabled = ready,
            onClick = { onSelect(mode) },
            label = { Text(mode.name) },
            modifier = chipModifier.heightIn(min = 48.dp)
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!horizontal) Text(stringResource(R.string.driving_mode), style = MaterialTheme.typography.titleMedium)
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.driving_mode), Modifier.weight(1.1f), style = MaterialTheme.typography.titleMedium)
                DrivingMode.entries.forEach { mode -> modeChip(mode, Modifier.weight(1f)) }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DrivingMode.entries.forEach { mode -> modeChip(mode, Modifier) }
            }
        }
        if (!ready) SupportingText(stringResource(if (hasError) R.string.mode_error else R.string.mode_loading))
        SupportingText(stringResource(R.string.mode_consumption, vehicle.consumptionKwhPerKm * 100))
    }
}

@Composable
internal fun History(history: HistoryState, expanded: Boolean, onDelete: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = if (expanded) GridCells.Adaptive(320.dp) else GridCells.Fixed(1),
        modifier = Modifier.widthIn(max = 1440.dp).fillMaxSize().testTag("history_list"),
        contentPadding = ContentPadding,
        horizontalArrangement = ContentSpacing,
        verticalArrangement = ContentSpacing
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenHeading(R.string.history, R.string.history_description, expanded)
                Text("Completed navigation trips", style = MaterialTheme.typography.titleLarge)
                SupportingText("DEMO driving-endpoint completions · newest first")
                when {
                    history.completedTripsError -> SupportingText("Could not load completed navigation trips. Retrying…")
                    history.completedTripsLoading -> SupportingText("Loading completed navigation trips…")
                    history.completedNavigationTrips.isEmpty() -> SupportingText("Complete a DEMO driving route to save it here. Paused or cancelled trips are not saved.")
                }
            }
        }
        items(history.completedNavigationTrips, key = { "navigation:${it.completionId}" }) { trip -> CompletedNavigationTripCard(trip) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Vehicle snapshots", style = MaterialTheme.typography.titleLarge)
                SupportingText("Vehicle simulation samples · separate from completed navigation")
                SupportingText(pluralStringResource(R.plurals.history_count, history.snapshots.size, history.snapshots.size))
                HistoryNotice(history)
            }
        }
        items(history.snapshots, key = { "snapshot:${it.id}" }) { snapshot ->
            SnapshotCard(snapshot) { onDelete(snapshot.id) }
        }
    }
}

@Composable
internal fun HistoryNotice(history: HistoryState) {
    when {
        history.hasError -> SupportingText(stringResource(R.string.history_error))
        history.isLoading -> SupportingText(stringResource(R.string.loading_history))
        history.snapshots.isEmpty() -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.empty_history_title), style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(R.string.empty_history_description))
            }
        }
    }
}

@Composable
internal fun SnapshotCard(snapshot: TripSnapshot, onDelete: (() -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(snapshot.savedAtMillis, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .format(Date(snapshot.savedAtMillis))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.snapshot_title, snapshot.id), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                onDelete?.let {
                    val description = stringResource(R.string.delete_snapshot_accessibility, snapshot.id)
                    TextButton(onClick = it, modifier = Modifier.semantics { contentDescription = description }) {
                        Text(stringResource(R.string.delete_snapshot), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text(stringResource(R.string.distance_value, snapshot.distanceKm), style = MaterialTheme.typography.titleLarge)
            SupportingText(stringResource(R.string.snapshot_energy, snapshot.remainingEnergyKwh))
            SupportingText(stringResource(R.string.snapshot_mode, snapshot.drivingMode))
            SupportingText(date)
        }
    }
}

@Composable
private fun Settings(vehicle: VehicleStatus, ready: Boolean, hasError: Boolean, onModeChange: (DrivingMode) -> Unit, expanded: Boolean) {
    LazyVerticalGrid(
        columns = if (expanded) GridCells.Adaptive(340.dp) else GridCells.Fixed(1),
        modifier = Modifier.widthIn(max = 1440.dp).fillMaxSize().testTag("settings_list"),
        contentPadding = ContentPadding,
        horizontalArrangement = ContentSpacing,
        verticalArrangement = ContentSpacing
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { ScreenHeading(R.string.settings, R.string.settings_description, expanded) }
        item {
            InfoCard {
                SupportingText(stringResource(R.string.mode_description))
                ModeSelector(vehicle, ready, hasError, onModeChange)
            }
        }
        item {
            InfoCard {
                Text(stringResource(R.string.simulation_info), style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(R.string.simulation_description))
                SupportingText(stringResource(R.string.simulation_specs))
            }
        }
        item {
            InfoCard {
                Text(stringResource(R.string.session_info), style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(R.string.session_description))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SupportingText(stringResource(R.string.simulation_footer)) }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
internal fun ScreenHeading(@StringRes title: Int, @StringRes description: Int, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_dashboard), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(title), Modifier.padding(top = if (compact) 0.dp else 12.dp), style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
        SupportingText(stringResource(description))
    }
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConfirmationDialog(title: String, description: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
