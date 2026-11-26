package com.example.autohmi.navigation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autohmi.navigation.*
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun NavigationScreen(viewModel: NavigationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = viewModel.controller
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun permission(): LocationPermission = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPermission.PRECISE
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPermission.APPROXIMATE
        else -> LocationPermission.NONE
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller.setPermission(permission()) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { controller.setPermission(permission()); controller.setActive(true) }
                Lifecycle.Event.ON_RESUME -> { controller.setPermission(permission()); controller.requestFresh() }
                Lifecycle.Event.ON_STOP -> controller.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { controller.setPermission(permission()); controller.setActive(true) }
        onDispose { lifecycle.removeObserver(observer); controller.setActive(false) }
    }
    NavigationLayout(state,
        onPermission = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        onLocationSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
        onAppSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) } },
        onSelect = controller::selectDestination, onClear = controller::clearDestination,
        onCalculate = controller::calculateRoute, onStart = controller::startTrip, onEnd = controller::endTrip,
        onRecenter = controller::recenter, onOverview = controller::overview, onFollow = controller::toggleFollow,
        onTheme = { controller.setTheme(if (state.theme == MapTheme.DARK) MapTheme.LIGHT else MapTheme.DARK) },
        onArrivalRadius = { controller.setArrivalRadius(if (state.arrivalRadiusMeters >= 50) 25.0 else if (state.arrivalRadiusMeters < 35) 35.0 else 50.0) },
        map = { modifier, retry, onStatus ->
            NavigationMap(state, viewModel.camera, viewModel.mapMode, retry, controller::selectDestination,
                controller::manuallyExploring, viewModel::saveCamera, onStatus, modifier)
        }, onSimulate = controller::startDemoDrive, onStopSimulation = controller::stopDemoDrive,
        onResumeSimulation = controller::resumeDemoDrive, onExitDemo = controller::exitDemoDrive,
        onRetryCompletionSave = controller::retryCompletedTripSave)
}

@Composable
fun NavigationLayout(
    state: NavigationState,
    onPermission: () -> Unit,
    onLocationSettings: () -> Unit,
    onAppSettings: () -> Unit,
    onSelect: (GeoPoint, String?) -> Unit,
    onClear: () -> Unit,
    onCalculate: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onRecenter: () -> Unit,
    onOverview: () -> Unit,
    onFollow: () -> Unit,
    onTheme: () -> Unit,
    onArrivalRadius: () -> Unit,
    map: @Composable (Modifier, Int, (MapLoadStatus) -> Unit) -> Unit,
    onSimulate: () -> Unit = {},
    onStopSimulation: () -> Unit = {},
    onResumeSimulation: () -> Unit = {},
    onExitDemo: () -> Unit = {},
    onRetryCompletionSave: () -> Unit = {}
) {
    var coordinateDialog by rememberSaveable { mutableStateOf(false) }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    val colors = if (state.theme == MapTheme.LIGHT) lightColorScheme(primary = Color(0xFF006C55), secondary = Color(0xFF235E94)) else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize().clipToBounds(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("navigation_screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Navigation", style = MaterialTheme.typography.headlineSmall)
                    Text(when (state.demoDrive?.status) {
                        DemoDriveStatus.RUNNING -> "DEMO drive · 30 km/h"
                        DemoDriveStatus.PAUSED -> "DEMO paused · position retained"
                        DemoDriveStatus.ARRIVED -> "DEMO · Driving route complete"
                        null -> "Route planning · foreground GPS"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onTheme, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.theme == MapTheme.DARK) "Day map" else "Night map") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val panel: @Composable (Modifier) -> Unit = { modifier ->
                    NavigationPanel(state, onPermission, onLocationSettings, onAppSettings,
                        { coordinateDialog = true }, onClear, onCalculate, onStart, onEnd, onArrivalRadius, modifier,
                        onSimulate, onStopSimulation, onResumeSimulation, onExitDemo, onRetryCompletionSave)
                }
                val mapArea: @Composable (Modifier) -> Unit = { modifier ->
                    NavigationMapArea(state, onRecenter, onOverview, onFollow, map, modifier)
                }
                if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize()) {
                    SlidingNavigationPanel(sidebarCollapsed,
                        if (LocalDensity.current.fontScale > 1.15f) 380.dp else 350.dp,
                        onCollapse = { sidebarCollapsed = true }, panel = panel)
                    NavigationMapArea(state, onRecenter, onOverview, onFollow, map,
                        Modifier.weight(1f).fillMaxHeight(), sidebarCollapsed,
                        onToggleSidebar = { sidebarCollapsed = !sidebarCollapsed },
                        onExpandSidebar = { sidebarCollapsed = false })
                } else Column(Modifier.fillMaxSize()) {
                    mapArea(Modifier.fillMaxWidth().weight(1f).heightIn(min = 180.dp))
                    panel(Modifier.fillMaxWidth().heightIn(max = 300.dp))
                }
            }
        }
        }
        if (coordinateDialog) CoordinateDestinationDialog(
            onDismiss = { coordinateDialog = false },
            onConfirm = { point, name -> onSelect(point, name); coordinateDialog = false })
    }
}

@Composable
private fun NavigationMapArea(
    state: NavigationState, onRecenter: () -> Unit, onOverview: () -> Unit, onFollow: () -> Unit,
    map: @Composable (Modifier, Int, (MapLoadStatus) -> Unit) -> Unit, modifier: Modifier,
    sidebarCollapsed: Boolean? = null, onToggleSidebar: () -> Unit = {}, onExpandSidebar: () -> Unit = {}
) {
    var status by remember { mutableStateOf(MapLoadStatus.LOADING) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry, state.theme, status) {
        if (status == MapLoadStatus.LOADING) { delay(20_000); status = MapLoadStatus.ERROR }
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        val baseHudDiameter = if (maxWidth < 440.dp) 100.dp else if (LocalDensity.current.fontScale >= 1.15f) 120.dp else 112.dp
        // Enlarge by 12.5% when there is room for the HUD and existing edge controls.
        val hudDiameter = baseHudDiameter * if (maxWidth >= 320.dp && maxHeight >= 320.dp) 1.125f else 1f
        map(Modifier.fillMaxSize().testTag("navigation_map"), retry) { status = it }
        NavigationSpeedometer(state.speedometer, Modifier.align(Alignment.TopStart).padding(12.dp).size(hudDiameter))
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("navigation_map_controls"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = onRecenter, enabled = state.mapVehiclePosition != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Recenter") }
            FilledTonalButton(onClick = onOverview, enabled = state.destination != null || state.mapVehiclePosition != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Overview") }
            FilterChip(selected = state.followLocation, onClick = onFollow,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                label = { Text(if (state.followLocation) "Following" else "Follow location") })
        }
        if (sidebarCollapsed == true && state.route != null) {
            // The collapsed map is at least 700 dp wide: reserve both upper corner control areas.
            NavigationTripInformation(state, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                .width(minOf(320.dp, (maxWidth - 360.dp).coerceAtLeast(0.dp)))
                .heightIn(max = (maxHeight - 132.dp).coerceAtLeast(48.dp))
                .verticalScroll(rememberScrollState()), compact = true)
        }
        if (status != MapLoadStatus.READY) Surface(Modifier.align(Alignment.TopStart).padding(start = if (sidebarCollapsed != null) 60.dp else 8.dp, top = hudDiameter + 24.dp)
            .testTag("navigation_map_status"), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
            Column(Modifier.padding(10.dp).widthIn(max = 210.dp)) {
                if (status == MapLoadStatus.LOADING) {
                    Text("Loading map…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    Text("Map unavailable. Check your connection.")
                    TextButton(onClick = { status = MapLoadStatus.LOADING; retry++ }) { Text("Retry map") }
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 32.dp)
            .widthIn(max = minOf(280.dp, (maxWidth - 176.dp).coerceAtLeast(96.dp))).testTag("navigation_marker_legend"),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(6.dp)) {
                if (state.demoDrive != null) {
                    Text("Cyan: DEMO · Blue: GPS", style = MaterialTheme.typography.labelSmall)
                    Text("Orange: destination", style = MaterialTheme.typography.labelSmall)
                } else Text("Blue: you · Orange: destination", style = MaterialTheme.typography.labelSmall)
                if (state.route != null) Text("Purple: driving endpoint", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (sidebarCollapsed != null) {
            if (sidebarCollapsed) Box(Modifier.align(Alignment.CenterStart).width(SidebarEdgeWidth).fillMaxHeight()
                .testTag("navigation_sidebar_edge_zone")
                .sidebarSwipe(direction = 1, onSwipe = onExpandSidebar))
            // Below the HUD, inside the map's existing safe bounds; 48 dp accessible touch target.
            val handleTop = maxOf(hudDiameter + 24.dp, (maxHeight - 48.dp) / 2)
                .coerceAtMost((maxHeight - 48.dp).coerceAtLeast(0.dp))
            FilledTonalIconButton(onClick = onToggleSidebar, modifier = Modifier.align(Alignment.TopStart)
                .offset(y = handleTop).size(48.dp).testTag("navigation_sidebar_handle")
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), CircleShape)
                .semantics {
                    contentDescription = if (sidebarCollapsed) "Show navigation information" else "Hide navigation information"
                    stateDescription = if (sidebarCollapsed) "Collapsed" else "Expanded"
                }
                .sidebarSwipe(direction = if (sidebarCollapsed) 1 else -1,
                    startZone = if (sidebarCollapsed) SidebarEdgeWidth else null, onSwipe = onToggleSidebar),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary)) {
                Text(if (sidebarCollapsed) "›" else "‹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun NavigationPanel(
    state: NavigationState,
    onPermission: () -> Unit,
    onLocationSettings: () -> Unit,
    onAppSettings: () -> Unit,
    onCoordinates: () -> Unit,
    onClear: () -> Unit,
    onCalculate: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onArrivalRadius: () -> Unit,
    modifier: Modifier = Modifier,
    onSimulate: () -> Unit = {},
    onStopSimulation: () -> Unit = {},
    onResumeSimulation: () -> Unit = {},
    onExitDemo: () -> Unit = {},
    onRetryCompletionSave: () -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val demo = state.demoDrive
    val demoArrived = demo?.status == DemoDriveStatus.ARRIVED
    BoxWithConstraints(modifier.clipToBounds().background(MaterialTheme.colorScheme.surface).testTag("navigation_panel")) {
    val actionHeightLimit = maxHeight * 0.6f
    Column(Modifier.fillMaxSize()) {
        // Keep DEMO actions outside the details scroller, including on short Automotive displays.
        if (demo != null || (state.route != null && state.phase in setOf(TripPhase.ROUTE_READY, TripPhase.TRIP_ACTIVE, TripPhase.ARRIVED))) {
            Column(Modifier.fillMaxWidth().heightIn(max = actionHeightLimit)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("navigation_panel_actions"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (demo == null) {
                    Button(onClick = onSimulate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("simulate_drive_action")) {
                        Text("Simulate drive (DEMO)")
                    }
                    Text("Starts at the beginning of the planned route.", style = MaterialTheme.typography.bodySmall)
                } else if (demoArrived) {
                    Surface(Modifier.fillMaxWidth().testTag("demo_arrival_confirmation").semantics { liveRegion = LiveRegionMode.Polite },
                        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("DEMO · Driving route complete", style = MaterialTheme.typography.labelLarge)
                            Text("Destination reached", style = MaterialTheme.typography.headlineSmall)
                            Text("Driving endpoint reached · 100% complete", style = MaterialTheme.typography.bodySmall)
                            Text("0 m remaining driving distance", style = MaterialTheme.typography.titleMedium)
                            state.route?.takeIf { it.hasDestinationGap }?.let { route ->
                                Text("Selected destination is approximately ${route.destinationGapMeters.roundToInt()} m from this driving endpoint. Gap excluded from the driving route.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedButton(onClick = onExitDemo, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("exit_demo_action")) { Text("Exit DEMO") }
                } else {
                    if (demo.status == DemoDriveStatus.PAUSED) Text("DEMO paused", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = if (demo.status == DemoDriveStatus.PAUSED) onResumeSimulation else onStopSimulation,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("pause_resume_demo_action")) {
                            Text(if (demo.status == DemoDriveStatus.PAUSED) "Resume DEMO" else "Pause DEMO")
                        }
                        OutlinedButton(onClick = onExitDemo, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("exit_demo_action")) { Text("Exit DEMO") }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp).testTag("navigation_panel_details"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NavigationTripInformation(state, Modifier.fillMaxWidth())
        if (demo != null) Surface(Modifier.fillMaxWidth().testTag("demo_drive_status"), shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (demo.status) {
                    DemoDriveStatus.RUNNING -> "DEMO · Simulated drive"
                    DemoDriveStatus.PAUSED -> "DEMO · Paused drive"
                    DemoDriveStatus.ARRIVED -> "DEMO · Completed drive"
                }, style = MaterialTheme.typography.titleMedium)
                Text("${(demo.progressFraction * 100).toInt()}% along the road geometry · device GPS unchanged", style = MaterialTheme.typography.bodySmall)
                Text(String.format(Locale.US, "DEMO position: %.5f, %.5f", demo.position.latitude, demo.position.longitude), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { demo.progressFraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(if (demo != null) "Device GPS: ${locationMessage(state.location)}" else locationMessage(state.location), style = MaterialTheme.typography.titleSmall)
        if (state.location.permission == LocationPermission.NONE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPermission, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enable location") }
                TextButton(onClick = onAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("App settings") }
            }
        } else if (state.location.permission == LocationPermission.APPROXIMATE) {
            Text("Approximate position. Precise location is needed for trip progress and arrival.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onPermission) { Text("Improve location accuracy") }
        }
        if (state.location.status == LocationStatus.SERVICES_DISABLED) Button(onClick = onLocationSettings) { Text("Open location settings") }
        state.location.fix?.let { fix ->
            Text(String.format(Locale.US, if (demo != null) "Device GPS position: %.5f, %.5f · ±%.0f m%s" else "Position: %.5f, %.5f · ±%.0f m%s", fix.point.latitude, fix.point.longitude, fix.accuracyMeters,
                if (fix.simulated) " · mock fix" else ""), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(state.destination?.name ?: "Where to?", style = MaterialTheme.typography.titleMedium)
                state.destination?.point?.let { Text(String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude), style = MaterialTheme.typography.bodySmall) }
                    ?: Text("Long-press the map or enter coordinates.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onCoordinates, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.destination == null) "Set destination" else "Change") }
        }
        if (!state.routingConfigured) Text("Live routing is not configured. Map browsing and destination selection remain available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.issue?.let { Text(navigationIssueMessage(it), color = MaterialTheme.colorScheme.error) }
        if (state.issue == NavigationIssue.GPS_JUMP) TextButton(onClick = onEnd) { Text("Use new GPS position") }
        Text(if (demo != null) when (demo.status) {
            DemoDriveStatus.RUNNING -> "DEMO trip active · simulated movement"
            DemoDriveStatus.PAUSED -> "Simulation paused · resume to continue"
            DemoDriveStatus.ARRIVED -> "DEMO arrived at the driving endpoint"
        } else when (state.phase) {
            TripPhase.IDLE -> "Ready to explore"
            TripPhase.DESTINATION_SELECTED -> "Destination selected"
            TripPhase.CALCULATING -> "Calculating driving route…"
            TripPhase.ROUTE_READY -> "Driving route ready"
            TripPhase.TRIP_ACTIVE -> "Trip active · GPS progress"
            TripPhase.ARRIVED -> "Arrived at the driving endpoint"
            TripPhase.ERROR -> "Route unavailable"
        }, style = MaterialTheme.typography.titleSmall)
        if (state.phase == TripPhase.CALCULATING) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (demoArrived) when (state.completionSaveStatus) {
            CompletionSaveStatus.NONE -> Unit
            CompletionSaveStatus.SAVING -> Text("Saving completed trip to History…", style = MaterialTheme.typography.bodySmall)
            CompletionSaveStatus.SAVED -> Text("Completed DEMO trip saved in History", style = MaterialTheme.typography.bodySmall)
            CompletionSaveStatus.FAILED -> {
                Text("Completed trip could not be saved. Retry before exiting DEMO.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryCompletionSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry saving trip") }
            }
        }
        state.route?.let { route ->
            Text(if (demoArrived) "Completed driving route · ${distanceLabel(route.distanceMeters)} total"
                else "${distanceLabel(route.distanceMeters)} · ${durationLabel(route.durationSeconds)} driving estimate", style = MaterialTheme.typography.titleLarge)
            if (route.hasDestinationGap) Surface(Modifier.fillMaxWidth().testTag("route_endpoint_gap"),
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Driving route ends approximately ${route.destinationGapMeters.roundToInt()} meters from your destination.", style = MaterialTheme.typography.bodyMedium)
                    Text("Gap excluded from driving distance and ETA. Access beyond the endpoint is unknown.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(route.attribution, style = MaterialTheme.typography.labelSmall)
            FlowRow {
                TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Map data credits") }
                TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/fixthemap") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fix map") }
            }
        }
        FlowRow(maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.15f) 1 else 2,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCalculate, enabled = demo == null && state.routingConfigured && state.destination != null && state.location.fix != null && state.phase != TripPhase.CALCULATING,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (state.route == null) "Plan route" else "Recalculate") }
            if (demo == null && state.phase in setOf(TripPhase.TRIP_ACTIVE, TripPhase.ARRIVED)) {
                OutlinedButton(onClick = onEnd, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("End trip") }
            } else if (demo == null && state.phase == TripPhase.ROUTE_READY) {
                OutlinedButton(onClick = onStart, enabled = state.location.permission == LocationPermission.PRECISE && state.issue == null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start trip") }
            }
        }
        FlowRow {
            TextButton(onClick = onClear, enabled = state.destination != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear destination") }
            TextButton(onClick = onArrivalRadius, modifier = Modifier.heightIn(min = 48.dp)) { Text("Arrival: ${state.arrivalRadiusMeters.toInt()} m") }
        }
        Text("Road-route planning and GPS progress only. No turn-by-turn guidance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

@Composable
private fun CoordinateDestinationDialog(onDismiss: () -> Unit, onConfirm: (GeoPoint, String?) -> Unit) {
    var latitude by rememberSaveable { mutableStateOf("") }; var longitude by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val point = runCatching { GeoPoint(latitude.toDouble(), longitude.toDouble()) }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Destination coordinates") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Enter decimal degrees. No address-search service is configured.")
            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.testTag("destination_latitude"))
            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.testTag("destination_longitude"))
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Name (optional)") }, singleLine = true)
            if ((latitude.isNotBlank() || longitude.isNotBlank()) && point == null) Text("Use latitude −90…90 and longitude −180…180.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { point?.let { onConfirm(it, name) } }, enabled = point != null) { Text("Use destination") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

fun distanceLabel(meters: Double): String = if (meters < 1000) "${meters.toInt()} m" else String.format(Locale.US, "%.1f km", meters / 1000)
fun durationLabel(seconds: Double): String { val minutes = kotlin.math.ceil(seconds / 60).toInt(); return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min" }
fun locationMessage(state: LocationState): String = when (state.status) {
    LocationStatus.IDLE -> "Location paused"
    LocationStatus.DENIED -> "Location permission is off"
    LocationStatus.SERVICES_DISABLED -> "Device location services are off"
    LocationStatus.SEARCHING -> "Acquiring a fresh location…"
    LocationStatus.AVAILABLE -> if (state.permission == LocationPermission.APPROXIMATE) "Approximate location" else "GPS position available"
    LocationStatus.UNAVAILABLE -> "No fresh location. Check emulator Location controls."
    LocationStatus.UNSUPPORTED -> "Location provider is unavailable on this device"
}
fun navigationIssueMessage(issue: NavigationIssue): String = when (issue) {
    NavigationIssue.ROUTING_NOT_CONFIGURED -> "Live routing is not configured. No driving route has been calculated."
    NavigationIssue.NO_LOCATION -> "A current device location is required. Enable location and wait for a fix."
    NavigationIssue.STALE_LOCATION -> "Location is stale. Trip estimates are paused until fresh GPS is available."
    NavigationIssue.PRECISE_REQUIRED -> "Allow precise location to track a trip and detect arrival."
    NavigationIssue.LOW_ACCURACY -> "GPS accuracy is too low for reliable trip progress."
    NavigationIssue.NO_ROUTE -> "No driving route connects these points. Try a destination near a road."
    NavigationIssue.NETWORK -> "The routing service could not be reached. Check your connection and retry."
    NavigationIssue.SERVER -> "The routing service is unavailable. Retry later."
    NavigationIssue.RATE_LIMITED -> "The routing service asked you to wait before retrying."
    NavigationIssue.INVALID_ROUTE -> "The provider returned an unusable road route. Try a point closer to a road."
    NavigationIssue.ORIGIN_MOVED -> "Your starting position changed. Plan a fresh route before starting."
    NavigationIssue.GPS_JUMP -> "GPS continuity was lost. Use the new position and plan again; arrival is paused."
    NavigationIssue.OFF_ROUTE -> "You are away from the planned route. Estimates are paused; recalculate when ready."
}
