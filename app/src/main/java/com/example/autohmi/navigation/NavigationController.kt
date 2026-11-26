package com.example.autohmi.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import com.example.autohmi.CompletedNavigationTrip
import com.example.autohmi.CompletedTripRecorder

/** Foreground GPS navigation and opt-in DEMO progress; no background service or auto rerouting. */
class NavigationController(
    private val locations: LocationRepository,
    private val routes: RouteRepository,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val completedTripRecorder: CompletedTripRecorder? = null
) {
    private val _state = MutableStateFlow(NavigationState(routingConfigured = routes.configured))
    val state = _state.asStateFlow()
    private var routeJob: Job? = null
    private var tickJob: Job? = null
    private var demoJob: Job? = null
    private var demoPath: RoutePath? = null
    private var resumeDemoWhenActive = false
    private var demoStatistics: DemoTripStatistics? = null
    private var completedDemoTrip: CompletedNavigationTrip? = null
    private var completionWaiter: Job? = null
    private var active = false
    private var generation = 0L
    private var acceptedFix: LocationFix? = null
    private var gpsSpeedRejected = false
    private var lastRawTimestamp = -1L
    private var lastAlong = 0.0
    private var arrivalSamples = 0
    private var continuityLost = false
    private var routeCreatedAt = 0L
    private val collectJob = scope.launch { locations.state.collect(::onLocation) }

    // Publish speed with the same StateFlow and monotonic clock as navigation. Equal readings
    // remain equal across position-only updates; no extra UI timer or speed simulation is needed.
    private fun updateState(transform: (NavigationState) -> NavigationState) {
        _state.update { current ->
            val next = transform(current)
            next.copy(gpsSpeedKmh = if (active && !gpsSpeedRejected) next.location.validGpsSpeedKmh(elapsed()) else null)
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        locations.setActive(value)
        tickJob?.cancel(); tickJob = null
        if (!value) {
            if (_state.value.demoDrive != null) {
                val wasRunning = _state.value.demoDrive?.status == DemoDriveStatus.RUNNING
                stopDemoDrive()
                resumeDemoWhenActive = wasRunning
                return
            }
            if (_state.value.phase == TripPhase.CALCULATING) {
                invalidateRequest()
                updateState { it.copy(phase = if (it.destination == null) TripPhase.IDLE else TripPhase.DESTINATION_SELECTED) }
            }
            clearProgress()
        } else {
            onLocation(locations.state.value)
            val resumeDemo = resumeDemoWhenActive
            resumeDemoWhenActive = false
            if (resumeDemo) resumeDemoDrive()
            tickJob = scope.launch { while (isActive) { checkFreshness(); delay(1_000) } }
        }
    }
    fun setPermission(permission: LocationPermission) { locations.setPermission(permission) }
    fun requestFresh() { locations.requestFresh() }
    private fun invalidateRequest() { generation++; routeJob?.cancel(); routeJob = null }
    fun selectDestination(point: GeoPoint, name: String? = null) {
        cancelDemoClock()
        invalidateRequest(); arrivalSamples = 0; lastAlong = 0.0
        val label = name?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: "Pinned destination"
        updateState { it.copy(destination = Destination(point, label), route = null, phase = TripPhase.DESTINATION_SELECTED,
            issue = null, remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null, demoDrive = null,
            completionSaveStatus = CompletionSaveStatus.NONE) }
    }
    fun clearDestination() {
        cancelDemoClock()
        invalidateRequest(); arrivalSamples = 0
        updateState { it.copy(destination = null, route = null, phase = TripPhase.IDLE, issue = null,
            remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null, demoDrive = null,
            completionSaveStatus = CompletionSaveStatus.NONE) }
    }
    fun calculateRoute() {
        if (!active || _state.value.demoDrive != null) return
        val current = _state.value
        val destination = current.destination ?: return
        if (current.phase == TripPhase.CALCULATING) return
        if (!routes.configured) { fail(NavigationIssue.ROUTING_NOT_CONFIGURED); return }
        val fix = usableFix() ?: return
        invalidateRequest()
        val attempt = generation
        updateState { it.copy(phase = TripPhase.CALCULATING, route = null, issue = null, remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null) }
        routeJob = scope.launch {
            val result = try { routes.calculate(fix.point, destination) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RouteResult.Failure(NavigationIssue.SERVER) }
            if (attempt != generation || !active) return@launch
            when (result) {
                is RouteResult.Failure -> fail(result.issue)
                is RouteResult.Success -> {
                    val latest = acceptedFix
                    if (latest == null || GeoMath.distance(latest.point, fix.point) > 100 + latest.accuracyMeters) {
                        fail(NavigationIssue.ORIGIN_MOVED); return@launch
                    }
                    val route = result.route
                    if (route.origin != fix.point || route.destination.point != destination.point || !validGeometry(route)) {
                        fail(NavigationIssue.INVALID_ROUTE); return@launch
                    }
                    routeCreatedAt = elapsed(); lastAlong = 0.0; arrivalSamples = 0
                    updateState { it.copy(route = route, destination = route.destination, phase = TripPhase.ROUTE_READY, issue = null, followLocation = false,
                        cameraRequest = CameraRequest(it.cameraRequest.id + 1, CameraAction.ROUTE)) }
                }
            }
        }
    }
    private fun validGeometry(route: RoutePlan): Boolean {
        val projection = GeoMath.project(route.geometry.first(), route.geometry)
        return projection.lengthMeters > 1 && route.distanceMeters >= projection.lengthMeters * 0.5 &&
            GeoMath.distance(route.origin, route.geometry.first()) <= 300 &&
            GeoMath.distance(route.snappedDestination, route.drivingEndpoint) <= RoutePlan.ENDPOINT_TOLERANCE_METERS
    }
    fun startTrip() {
        if (_state.value.demoDrive != null) return // Start Trip always means device GPS navigation.
        val route = _state.value.route ?: return
        if (_state.value.phase != TripPhase.ROUTE_READY) return
        val fix = usableFix(requirePrecise = true) ?: return
        if (elapsed() - routeCreatedAt > 15 * 60_000 || GeoMath.distance(fix.point, route.origin) > 100 + fix.accuracyMeters) {
            fail(NavigationIssue.ORIGIN_MOVED); return
        }
        arrivalSamples = 0; lastAlong = 0.0; continuityLost = false
        updateState { it.copy(phase = TripPhase.TRIP_ACTIVE, followLocation = true, issue = null,
            cameraRequest = CameraRequest(it.cameraRequest.id + 1, CameraAction.RECENTER)) }
        updateTrip(fix, countArrival = false)
    }
    fun startDemoDrive() {
        val current = _state.value
        val route = current.route ?: return
        if (!active || current.demoDrive != null || current.phase !in setOf(TripPhase.ROUTE_READY, TripPhase.TRIP_ACTIVE, TripPhase.ARRIVED) || !validGeometry(route)) return
        cancelDemoClock()
        demoStatistics = DemoTripStatistics()
        val path = RoutePath(route.geometry).also { demoPath = it }
        updateState { it.copy(demoDrive = DemoDriveState(path.pointAt(0.0), 0.0, path.lengthMeters),
            phase = TripPhase.TRIP_ACTIVE, issue = null, followLocation = true, completionSaveStatus = CompletionSaveStatus.NONE,
            cameraRequest = CameraRequest(it.cameraRequest.id + 1, CameraAction.RECENTER)) }
        runDemoClock()
    }
    private fun runDemoClock() {
        val path = demoPath ?: return
        val route = _state.value.route ?: return
        val demo = _state.value.demoDrive ?: return
        demoJob?.cancel()
        applyDemoProgress(route, path, demo.traveledMeters)
        var previousTick = elapsed() // A fresh anchor excludes time spent off-screen.
        demoJob = scope.launch {
            while (isActive && active && _state.value.demoDrive?.status == DemoDriveStatus.RUNNING) {
                delay(1_000)
                if (!active || _state.value.route !== route) break
                val current = _state.value.demoDrive ?: break
                val now = elapsed()
                val delta = (now - previousTick).coerceAtLeast(0)
                previousTick = now
                applyDemoProgress(route, path, current.traveledMeters + delta / 1000.0 * (DemoDriveState.SPEED_KMH / 3.6), delta / 1000.0)
            }
        }
    }
    private fun applyDemoProgress(route: RoutePlan, path: RoutePath, traveled: Double, tickSeconds: Double = 0.0) {
        val meters = traveled.coerceIn(0.0, path.lengthMeters)
        demoStatistics?.observeStep(_state.value.demoDrive?.traveledMeters ?: 0.0, traveled, meters, tickSeconds)
        val fraction = meters / path.lengthMeters
        val arrived = meters >= path.lengthMeters
        val seconds = (path.lengthMeters - meters) / (DemoDriveState.SPEED_KMH / 3.6)
        updateState { it.copy(
            demoDrive = DemoDriveState(path.pointAt(meters), meters, path.lengthMeters,
                if (arrived) DemoDriveStatus.ARRIVED else DemoDriveStatus.RUNNING),
            phase = if (arrived) TripPhase.ARRIVED else TripPhase.TRIP_ACTIVE,
            remainingMeters = if (arrived) 0.0 else route.distanceMeters * (1 - fraction),
            remainingSeconds = seconds, estimatedArrivalEpochMs = if (arrived) null else wallTime() + (seconds * 1000).toLong(), issue = null) }
        if (arrived) {
            val trip = demoStatistics?.complete(route, requireNotNull(_state.value.demoDrive), wallTime())
            if (trip != null) { completedDemoTrip = trip; saveCompletedDemoTrip() }
        }
    }
    private fun cancelDemoClock() {
        demoJob?.cancel(); demoJob = null; demoPath = null; resumeDemoWhenActive = false
        demoStatistics = null; completedDemoTrip = null; completionWaiter?.cancel(); completionWaiter = null
    }
    fun retryCompletedTripSave() { if (_state.value.completionSaveStatus == CompletionSaveStatus.FAILED) saveCompletedDemoTrip() }
    private fun saveCompletedDemoTrip() {
        val trip = completedDemoTrip ?: return
        val recorder = completedTripRecorder ?: return
        updateState { it.copy(completionSaveStatus = CompletionSaveStatus.SAVING) }
        val write = recorder.record(trip) // App scope owns the Room transaction, not the screen.
        completionWaiter = scope.launch {
            val result = try { write.await().getOrThrow(); CompletionSaveStatus.SAVED }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CompletionSaveStatus.FAILED }
            if (completedDemoTrip?.completionId == trip.completionId && _state.value.demoDrive?.status == DemoDriveStatus.ARRIVED) {
                updateState { it.copy(completionSaveStatus = result) }
            }
        }
    }
    /** Stop movement only; retain the simulated vehicle and route progress until Exit DEMO. */
    fun stopDemoDrive() {
        resumeDemoWhenActive = false
        val demo = _state.value.demoDrive ?: return
        if (demo.status != DemoDriveStatus.RUNNING) return
        demoJob?.cancel(); demoJob = null
        updateState { it.copy(demoDrive = demo.copy(status = DemoDriveStatus.PAUSED),
            remainingSeconds = null, estimatedArrivalEpochMs = null) }
    }
    fun resumeDemoDrive() {
        if (!active || _state.value.demoDrive?.status != DemoDriveStatus.PAUSED) return
        resumeDemoWhenActive = false
        runDemoClock() // Continue with the same distance index and a fresh monotonic clock anchor.
    }
    fun exitDemoDrive() {
        if (_state.value.demoDrive == null) return
        cancelDemoClock()
        updateState { it.copy(demoDrive = null, phase = if (it.route != null) TripPhase.ROUTE_READY else it.phase,
            remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null, issue = null,
            completionSaveStatus = CompletionSaveStatus.NONE) }
    }
    fun endTrip() {
        cancelDemoClock()
        invalidateRequest(); arrivalSamples = 0; lastAlong = 0.0; continuityLost = false
        acceptedFix = locations.state.value.fix?.takeIf { fresh(it) }
        gpsSpeedRejected = false
        updateState { it.copy(route = null, phase = if (it.destination == null) TripPhase.IDLE else TripPhase.DESTINATION_SELECTED,
            issue = null, remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null,
            location = locations.state.value.copy(fix = acceptedFix), demoDrive = null, completionSaveStatus = CompletionSaveStatus.NONE) }
    }
    fun setArrivalRadius(meters: Double) { require(meters in 15.0..100.0); arrivalSamples = 0; updateState { it.copy(arrivalRadiusMeters = meters) } }
    fun setTheme(theme: MapTheme) { updateState { it.copy(theme = theme) } }
    fun manuallyExploring() { updateState { it.copy(followLocation = false) } }
    fun toggleFollow() {
        updateState { it.copy(followLocation = !it.followLocation,
            cameraRequest = if (!it.followLocation) CameraRequest(it.cameraRequest.id + 1, CameraAction.RECENTER) else it.cameraRequest) }
    }
    fun recenter() {
        locations.requestFresh()
        updateState { it.copy(followLocation = true, cameraRequest = CameraRequest(it.cameraRequest.id + 1, CameraAction.RECENTER)) }
    }
    fun overview() { updateState { it.copy(followLocation = false, cameraRequest = CameraRequest(it.cameraRequest.id + 1, CameraAction.OVERVIEW)) } }

    private fun fresh(fix: LocationFix) = elapsed() - fix.elapsedMs in 0..15_000
    private fun usableFix(requirePrecise: Boolean = false): LocationFix? {
        val current = _state.value
        val fix = acceptedFix
        val issue = when {
            current.location.permission == LocationPermission.NONE || fix == null || current.location.status != LocationStatus.AVAILABLE -> NavigationIssue.NO_LOCATION
            !fresh(fix) -> NavigationIssue.STALE_LOCATION
            requirePrecise && current.location.permission != LocationPermission.PRECISE -> NavigationIssue.PRECISE_REQUIRED
            fix.accuracyMeters > if (requirePrecise) 50.0 else 2000.0 -> NavigationIssue.LOW_ACCURACY
            else -> null
        }
        if (issue != null) { updateState { it.copy(issue = issue) }; return null }
        return fix
    }
    private fun onLocation(location: LocationState) {
        val fix = location.fix
        if (_state.value.demoDrive != null) {
            // Retain device GPS independently. Its errors/movement cannot drive or cancel DEMO progress.
            if (location.permission == LocationPermission.NONE) acceptedFix = null
            else if (fix != null && location.status == LocationStatus.AVAILABLE && fresh(fix) && fix.elapsedMs > lastRawTimestamp) {
                acceptedFix = fix; lastRawTimestamp = fix.elapsedMs; gpsSpeedRejected = false
            }
            updateState { it.copy(location = location.copy(fix = acceptedFix)) }
            return
        }
        if (location.permission == LocationPermission.NONE) acceptedFix = null
        updateState { it.copy(location = location.copy(fix = acceptedFix)) }
        if (!active || location.permission == LocationPermission.NONE || location.status != LocationStatus.AVAILABLE || fix == null) {
            if (_state.value.phase == TripPhase.TRIP_ACTIVE) { arrivalSamples = 0; clearProgress(NavigationIssue.NO_LOCATION) }
            return
        }
        if (fix.elapsedMs <= lastRawTimestamp) return
        lastRawTimestamp = fix.elapsedMs
        if (!fresh(fix)) { arrivalSamples = 0; clearProgress(NavigationIssue.STALE_LOCATION); return }
        val previous = acceptedFix
        val deltaMs = previous?.let { fix.elapsedMs - it.elapsedMs } ?: 0L
        if (previous != null && deltaMs in 1..30_000 && GeoMath.distance(previous.point, fix.point) >
            max(150.0, deltaMs / 1000.0 * 80 + previous.accuracyMeters + fix.accuracyMeters)) {
            gpsSpeedRejected = true
            if (_state.value.phase == TripPhase.TRIP_ACTIVE) continuityLost = true
            arrivalSamples = 0; clearProgress(NavigationIssue.GPS_JUMP); return
        }
        acceptedFix = fix; gpsSpeedRejected = false
        updateState { it.copy(location = location) }
        if (_state.value.phase == TripPhase.TRIP_ACTIVE && previous != null && deltaMs > 15_000) continuityLost = true
        if (_state.value.phase == TripPhase.TRIP_ACTIVE) updateTrip(fix, countArrival = true,
            maximumAdvance = previous?.let { max(120.0, GeoMath.distance(it.point, fix.point) * 3 + it.accuracyMeters + fix.accuracyMeters) } ?: 120.0)
        else if (_state.value.phase == TripPhase.ROUTE_READY && _state.value.route?.let { GeoMath.distance(it.origin, fix.point) > 100 + fix.accuracyMeters } == true) {
            updateState { it.copy(route = null, phase = TripPhase.DESTINATION_SELECTED, issue = NavigationIssue.ORIGIN_MOVED) }
        } else if (_state.value.issue in setOf(NavigationIssue.NO_LOCATION, NavigationIssue.STALE_LOCATION, NavigationIssue.GPS_JUMP)) {
            updateState { it.copy(issue = null) }
        }
    }
    private fun updateTrip(fix: LocationFix, countArrival: Boolean, maximumAdvance: Double = Double.POSITIVE_INFINITY) {
        val current = _state.value; val route = current.route ?: return
        if (continuityLost) { arrivalSamples = 0; clearProgress(NavigationIssue.GPS_JUMP); return }
        if (current.location.permission != LocationPermission.PRECISE || fix.accuracyMeters > 50) {
            arrivalSamples = 0; clearProgress(if (current.location.permission != LocationPermission.PRECISE) NavigationIssue.PRECISE_REQUIRED else NavigationIssue.LOW_ACCURACY); return
        }
        val projection = GeoMath.project(fix.point, route.geometry)
        if (projection.offsetMeters > max(75.0, fix.accuracyMeters * 2)) {
            arrivalSamples = 0; clearProgress(NavigationIssue.OFF_ROUTE); return
        }
        // Prevent progress snapping across route loops/intersections after a GPS discontinuity.
        if (countArrival && kotlin.math.abs(projection.alongMeters - lastAlong) > maximumAdvance) {
            continuityLost = true; arrivalSamples = 0; clearProgress(NavigationIssue.GPS_JUMP); return
        }
        lastAlong = projection.alongMeters
        val fraction = (projection.alongMeters / projection.lengthMeters).coerceIn(0.0, 1.0)
        val remaining = route.distanceMeters * (1 - fraction)
        val seconds = route.durationSeconds * (1 - fraction)
        val nearEnd = GeoMath.distance(fix.point, route.geometry.last()) <= current.arrivalRadiusMeters &&
            fraction >= 0.95 && remaining <= current.arrivalRadiusMeters * 2 && fix.accuracyMeters <= current.arrivalRadiusMeters
        arrivalSamples = if (countArrival && nearEnd) arrivalSamples + 1 else 0
        val arrived = arrivalSamples >= 3
        updateState { it.copy(phase = if (arrived) TripPhase.ARRIVED else TripPhase.TRIP_ACTIVE, issue = null,
            remainingMeters = if (arrived) 0.0 else remaining, remainingSeconds = if (arrived) 0.0 else seconds,
            estimatedArrivalEpochMs = wallTime() + ((if (arrived) 0.0 else seconds) * 1000).toLong()) }
    }
    private fun checkFreshness() {
        if (_state.value.demoDrive != null) return
        val fix = acceptedFix ?: return
        if (!fresh(fix)) { arrivalSamples = 0; clearProgress(NavigationIssue.STALE_LOCATION) }
    }
    private fun clearProgress(issue: NavigationIssue? = _state.value.issue) {
        updateState { it.copy(issue = issue, remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null) }
    }
    private fun fail(issue: NavigationIssue) { updateState { it.copy(phase = TripPhase.ERROR, issue = issue, route = null,
        remainingMeters = null, remainingSeconds = null, estimatedArrivalEpochMs = null) } }
    fun close() { exitDemoDrive(); active = false; invalidateRequest(); tickJob?.cancel(); collectJob.cancel(); locations.close() }
}
