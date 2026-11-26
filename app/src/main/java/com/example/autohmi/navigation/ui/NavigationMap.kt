package com.example.autohmi.navigation.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.autohmi.navigation.*
import com.example.autohmi.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.log.Logger
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.*

enum class MapLoadStatus { LOADING, READY, ERROR }

/** AndroidView bridge: native map/camera work does not restart on GPS recomposition. */
@Composable
fun NavigationMap(
    state: NavigationState,
    initialCamera: MapCamera,
    mode: MapMode,
    retry: Int,
    onDestination: (GeoPoint, String?) -> Unit,
    onGesture: () -> Unit,
    onCamera: (MapCamera) -> Unit,
    onStatus: (MapLoadStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestDestination = rememberUpdatedState(onDestination)
    val latestGesture = rememberUpdatedState(onGesture)
    val latestCamera = rememberUpdatedState(onCamera)
    val latestStatus = rememberUpdatedState(onStatus)
    val handle = remember(context, mode) {
        NativeMapHandle(context, initialCamera, mode,
            { point, name -> latestDestination.value(point, name) }, { latestGesture.value() },
            { latestCamera.value(it) }, { latestStatus.value(it) })
    }
    DisposableEffect(handle, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> handle.start()
            Lifecycle.Event.ON_RESUME -> handle.resume()
            Lifecycle.Event.ON_PAUSE -> handle.pause()
            Lifecycle.Event.ON_STOP -> handle.stop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) handle.start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) handle.resume()
        onDispose { lifecycle.removeObserver(observer); handle.destroy() }
    }
    Box(modifier) {
        AndroidView(factory = { handle.view }, update = { handle.update(state, retry) }, modifier = Modifier.fillMaxSize())
        // Reserve the upper-left for the permanent speedometer; keep zoom above attribution.
        Row(Modifier.align(Alignment.BottomEnd)
            .padding(start = 12.dp, end = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapZoomButton("+", stringResource(R.string.map_zoom_in)) { handle.zoomBy(1.0) }
            MapZoomButton("−", stringResource(R.string.map_zoom_out)) { handle.zoomBy(-1.0) }
        }
    }
}

@Composable
private fun MapZoomButton(symbol: String, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(0.dp), shape = MaterialTheme.shapes.medium,
        modifier = Modifier.size(64.dp).semantics { contentDescription = label }) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Tracks user intent while GPS can interrupt an in-flight zoom animation. */
internal class MapZoomState(initial: MapCamera) {
    private var positioned = initial != MapCamera()
    private var selected: Double? = null
    fun step(current: Double, delta: Double, minimum: Double, maximum: Double): Double {
        val target = ((selected ?: current) + delta).coerceIn(minimum, maximum)
        positioned = true; selected = target
        return target
    }
    fun followZoom(current: Double, accuracyMeters: Double): Double {
        val target = selected ?: if (positioned) current else max(current, if (accuracyMeters > 200) 11.0 else 15.0)
        positioned = true
        return target
    }
    fun cameraChanged(zoom: Double? = null) { positioned = true; selected = zoom }
}

private class NativeMapHandle(
    context: Context,
    private val initial: MapCamera,
    private val mode: MapMode,
    private val onDestination: (GeoPoint, String?) -> Unit,
    private val onGesture: () -> Unit,
    private val onCamera: (MapCamera) -> Unit,
    private val onStatus: (MapLoadStatus) -> Unit
) {
    val view: MapView
    private var map: MapLibreMap? = null
    private val zoom = MapZoomState(initial)
    private var state = NavigationState()
    private var theme: MapTheme? = null
    private var retry = -1
    private var styleReady = false
    private var destroyed = false
    private var started = false
    private var resumed = false
    // Retained with the camera so recreation never replays a consumed overview/recenter.
    private var appliedCameraRequest = initial.appliedRequestId
    private var lastFix: LocationFix? = null
    private var lastDemoPoint: GeoPoint? = null
    private var lastFollowPoint: GeoPoint? = null
    private var lastDestination: Destination? = null
    private var lastRoute: RoutePlan? = null
    private var followWasEnabled = false
    private var status: MapLoadStatus? = null
    private var styleGeneration = 0L
    private fun status(value: MapLoadStatus) { if (!destroyed && status != value) { status = value; onStatus(value) } }
    init {
        MapLibre.getInstance(context.applicationContext)
        Logger.setVerbosity(Logger.NONE) // No tile/coordinate-bearing URLs in application logs.
        view = MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { status(MapLoadStatus.ERROR) }
        view.addOnRenderErrorListener { status(MapLoadStatus.ERROR) }
        view.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && styleReady) status(MapLoadStatus.READY)
        })
        view.getMapAsync { native ->
            if (destroyed) return@getMapAsync
            map = native
            native.cameraPosition = CameraPosition.Builder().target(initial.center.latLng()).zoom(initial.zoom).bearing(initial.bearing).tilt(initial.tilt).build()
            native.uiSettings.isAttributionEnabled = true
            native.addOnCameraIdleListener { saveCamera() }
            native.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                zoom.cameraChanged()
                onGesture()
            } }
            native.addOnMapLongClickListener { point ->
                val features = native.queryRenderedFeatures(native.projection.toScreenLocation(point))
                val name = features.firstNotNullOfOrNull { feature ->
                    listOf("name:en", "name").firstNotNullOfOrNull { key ->
                        runCatching { feature.getStringProperty(key)?.takeIf(String::isNotBlank) }.getOrNull()
                    }
                }
                onDestination(GeoPoint(point.latitude, point.longitude), name?.let { "Near $it" })
                true
            }
            reloadStyle()
        }
    }
    fun update(next: NavigationState, retry: Int) {
        state = next
        val reload = theme != next.theme || this.retry != retry
        this.retry = retry
        if (map == null || destroyed) return
        if (reload) { reloadStyle(); return }
        render()
    }
    fun zoomBy(delta: Double) {
        val native = map ?: return
        if (destroyed) return
        val target = zoom.step(native.cameraPosition.zoom, delta, native.minZoomLevel, native.maxZoomLevel)
        native.easeCamera(CameraUpdateFactory.zoomTo(target), 300)
    }
    private fun reloadStyle() {
        val native = map ?: return
        styleReady = false; theme = state.theme; status(MapLoadStatus.LOADING)
        val attempt = ++styleGeneration
        val style = if (mode == MapMode.OFFLINE_TEST) Style.Builder().fromJson(OFFLINE_STYLE)
        else Style.Builder().fromUri(if (state.theme == MapTheme.DARK) DARK_STYLE else LIGHT_STYLE)
        native.setStyle(style) { loaded ->
            if (destroyed || attempt != styleGeneration) return@setStyle
            if (mode != MapMode.OFFLINE_TEST && theme == MapTheme.DARK) {
                // Only the base dark style: retain road widths, zoom expressions and HMI overlays.
                mapOf("highway_minor" to "#282C30", "highway_major_inner" to "#25292D",
                    "highway_major_subtle" to "#383E44", "highway_motorway_subtle" to "#282C30",
                    "highway_major_casing" to "rgba(76,82,88,0.8)",
                    "highway_motorway_casing" to "rgba(76,82,88,0.8)").forEach { (id, color) ->
                    loaded.getLayerAs<LineLayer>(id)?.setProperties(lineColor(color))
                }
                loaded.getLayerAs<SymbolLayer>("highway_name_other")?.setProperties(textColor("#7F8993"))
                loaded.getLayerAs<SymbolLayer>("highway_name_motorway")?.setProperties(textColor("#919BA5"))
            }
            listOf("hmi-route", "hmi-accuracy", "hmi-location", "hmi-destination", "hmi-driving-endpoint", "hmi-demo-vehicle").forEach { loaded.addSource(GeoJsonSource(it, emptyFeatures())) }
            loaded.addLayer(FillLayer("hmi-accuracy-fill", "hmi-accuracy").withProperties(fillColor("#4EA5FF"), fillOpacity(0.16f)))
            loaded.addLayer(LineLayer("hmi-route-border", "hmi-route").withProperties(lineColor("#07352C"), lineWidth(9f), lineCap("round"), lineJoin("round")))
            loaded.addLayer(LineLayer("hmi-route-line", "hmi-route").withProperties(lineColor("#68E4BD"), lineWidth(5f), lineCap("round"), lineJoin("round")))
            // Add before the destination layer so a coincident endpoint never hides the orange pin.
            loaded.addLayer(CircleLayer("hmi-driving-endpoint-dot", "hmi-driving-endpoint").withProperties(circleColor("#A86FE8"), circleRadius(6f), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
            // The larger cyan ring stays distinguishable even where DEMO and the destination coincide.
            loaded.addLayer(CircleLayer("hmi-demo-vehicle-dot", "hmi-demo-vehicle").withProperties(circleColor("#00E5FF"), circleRadius(12f), circleStrokeWidth(3f), circleStrokeColor("#00323B")))
            loaded.addLayer(CircleLayer("hmi-destination-dot", "hmi-destination").withProperties(circleColor("#FFB66D"), circleRadius(10f), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
            loaded.addLayer(CircleLayer("hmi-location-dot", "hmi-location").withProperties(circleColor("#338CFF"), circleRadius(8f), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
            styleReady = true; lastFix = null; lastDestination = null; lastRoute = null
            render(force = true)
        }
    }
    private fun render(force: Boolean = false) {
        val native = map ?: return
        val style = native.style ?: return
        if (!styleReady || destroyed) return
        val fix = state.location.fix
        if (force || fix != lastFix) {
            style.getSourceAs<GeoJsonSource>("hmi-location")?.setGeoJson(fix?.let { Feature.fromGeometry(it.point.point()) }?.let { FeatureCollection.fromFeature(it) } ?: emptyFeatures())
            style.getSourceAs<GeoJsonSource>("hmi-accuracy")?.setGeoJson(fix?.let { FeatureCollection.fromFeature(Feature.fromGeometry(accuracyPolygon(it))) } ?: emptyFeatures())
            lastFix = fix
        }
        val demoPoint = state.demoDrive?.position
        if (force || demoPoint != lastDemoPoint) {
            style.getSourceAs<GeoJsonSource>("hmi-demo-vehicle")?.setGeoJson(demoPoint?.let { FeatureCollection.fromFeature(Feature.fromGeometry(it.point())) } ?: emptyFeatures())
            lastDemoPoint = demoPoint
        }
        val followPoint = state.mapVehiclePosition
        if (followPoint != null && state.followLocation && (force || followPoint != lastFollowPoint || !followWasEnabled)) {
            val level = zoom.followZoom(native.cameraPosition.zoom, if (demoPoint != null) 0.0 else fix?.accuracyMeters ?: 0.0)
                .coerceIn(native.minZoomLevel, native.maxZoomLevel)
            native.easeCamera(CameraUpdateFactory.newLatLngZoom(followPoint.latLng(), level), 500)
        }
        lastFollowPoint = followPoint
        if (force || lastDestination != state.destination) {
            style.getSourceAs<GeoJsonSource>("hmi-destination")?.setGeoJson(state.destination?.let { FeatureCollection.fromFeature(Feature.fromGeometry(it.point.point())) } ?: emptyFeatures())
            lastDestination = state.destination
        }
        if (force || lastRoute !== state.route) {
            style.getSourceAs<GeoJsonSource>("hmi-route")?.setGeoJson(state.route?.let { FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(it.geometry.map { point -> point.point() }))) } ?: emptyFeatures())
            style.getSourceAs<GeoJsonSource>("hmi-driving-endpoint")?.setGeoJson(state.route?.let { FeatureCollection.fromFeature(Feature.fromGeometry(it.drivingEndpoint.point())) } ?: emptyFeatures())
            lastRoute = state.route
        }
        followWasEnabled = state.followLocation
        if (state.cameraRequest.id != appliedCameraRequest) applyCameraRequest()
    }
    private fun applyCameraRequest() {
        val native = map ?: return
        if (view.width <= 0 || view.height <= 0) { view.post { if (!destroyed && view.width > 0) applyCameraRequest() }; return }
        val points = when (state.cameraRequest.action) {
            CameraAction.RECENTER -> listOfNotNull(state.mapVehiclePosition)
            CameraAction.OVERVIEW -> listOfNotNull(state.mapVehiclePosition, state.destination?.point)
            // Frame both the road route and the selected pin without extending the route line.
            CameraAction.ROUTE -> state.route?.let { it.geometry + it.destination.point }.orEmpty()
        }.distinct()
        if (points.isEmpty()) return
        if (points.size == 1) {
            val level = 15.0.coerceIn(native.minZoomLevel, native.maxZoomLevel)
            zoom.cameraChanged(level)
            native.easeCamera(CameraUpdateFactory.newLatLngZoom(points.first().latLng(), level), 500)
        }
        else {
            zoom.cameraChanged()
            val bounds = LatLngBounds.Builder().includes(points.map { it.latLng() }).build()
            val padding = (56 * view.resources.displayMetrics.density).roundToInt().coerceAtMost(min(view.width, view.height) / 4)
            native.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding), 600)
        }
        appliedCameraRequest = state.cameraRequest.id
    }
    private fun saveCamera() {
        if (destroyed) return
        val camera = map?.cameraPosition ?: return
        val target = camera.target ?: return
        onCamera(MapCamera(GeoPoint(target.latitude, target.longitude), camera.zoom, camera.bearing, camera.tilt, appliedCameraRequest))
    }
    fun start() { if (!started && !destroyed) { view.onStart(); started = true } }
    fun resume() { start(); if (!resumed && !destroyed) { view.onResume(); resumed = true } }
    fun pause() { if (resumed && !destroyed) { saveCamera(); view.onPause(); resumed = false } }
    fun stop() { pause(); if (started && !destroyed) { view.onStop(); started = false } }
    fun destroy() { if (!destroyed) { saveCamera(); stop(); destroyed = true; view.onDestroy(); map = null } }
    companion object {
        const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
        const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
        const val OFFLINE_STYLE = """{"version":8,"sources":{},"layers":[{"id":"test-background","type":"background","paint":{"background-color":"#182734"}}]}"""
        private fun emptyFeatures() = FeatureCollection.fromFeatures(emptyArray<Feature>())
        private fun GeoPoint.latLng() = LatLng(latitude, longitude)
        private fun GeoPoint.point() = Point.fromLngLat(longitude, latitude)
        private fun accuracyPolygon(fix: LocationFix): Polygon {
            val radius = fix.accuracyMeters.coerceIn(1.0, 10_000.0)
            val lat = Math.toRadians(fix.point.latitude)
            val ring = (0..48).map { index ->
                val angle = index * 2 * PI / 48
                val latitude = (fix.point.latitude + Math.toDegrees(radius * sin(angle) / 6_371_008.8)).coerceIn(-85.0, 85.0)
                val longitude = ((fix.point.longitude + Math.toDegrees(radius * cos(angle) / (6_371_008.8 * cos(lat).coerceAtLeast(0.01))) + 540) % 360) - 180
                Point.fromLngLat(longitude, latitude)
            }
            return Polygon.fromLngLats(listOf(ring))
        }
    }
}
