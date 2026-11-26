package com.example.autohmi.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.theme.AutoHMITheme
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicReference

/** Synthetic responses/offline style only: no routing, GPS or account requests. */
class NavigationEndpointTest {
    @get:Rule val compose = createDisplayContentRule()
    private val origin = GeoPoint(52.52, 13.405)
    private val roadEnd = GeoPoint(52.521, 13.408)
    private val selected = Destination(GeoPoint(52.5216, 13.4087), "Offline endpoint fixture")
    private fun route(destination: Destination = selected) = RoutePlan(origin, destination,
        listOf(origin, GeoPoint(52.52, 13.408), roadEnd), 400.0, 90.0, "Offline test fixture only", roadEnd)

    @Test fun gapWarningAndSeparateMarkersRenderAndClearWithoutChangingDrivingMetrics() {
        val planned = route()
        val state = mutableStateOf(NavigationState(destination = selected, route = planned, phase = TripPhase.ROUTE_READY,
            followLocation = false, cameraRequest = CameraRequest(1, CameraAction.ROUTE)))
        val status = AtomicReference(MapLoadStatus.LOADING)
        compose.setContent { AutoHMITheme {
            NavigationLayout(state.value, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {},
                map = { modifier, retry, callback -> NavigationMap(state.value, MapCamera(origin, 16.0), MapMode.OFFLINE_TEST,
                    retry, { _, _ -> }, {}, {}, { status.set(it); callback(it) }, modifier) })
        } }
        compose.waitUntil(30_000) { status.get() == MapLoadStatus.READY }
        compose.onNodeWithTag("route_endpoint_gap").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Driving route ends approximately", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Gap excluded from driving distance and ETA.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("400 m · 2 min driving estimate").assertIsDisplayed()
        compose.onNodeWithText("Purple: driving endpoint").assertIsDisplayed()
        val reference = AtomicReference<MapLibreMap>()
        onView(isAssignableFrom(MapView::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(MapView::class.java)
            override fun getDescription() = "Inspect offline endpoint markers"
            override fun perform(uiController: UiController, view: android.view.View) { (view as MapView).getMapAsync { reference.set(it) } }
        })
        compose.waitUntil(5_000) { reference.get() != null }
        val native = reference.get()
        fun markerAt(point: GeoPoint, layer: String): Boolean {
            val pixel = native.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
            return native.queryRenderedFeatures(pixel, layer).any { feature ->
                val position = feature.geometry() as? Point
                position != null && GeoMath.distance(point, GeoPoint(position.latitude(), position.longitude())) < 2.0
            }
        }
        compose.waitUntil(5_000) {
            var rendered = false
            compose.runOnIdle { rendered = markerAt(roadEnd, "hmi-driving-endpoint-dot") && markerAt(selected.point, "hmi-destination-dot") }
            rendered
        }
        compose.runOnIdle { state.value = state.value.copy(route = null, phase = TripPhase.DESTINATION_SELECTED) }
        compose.onNodeWithTag("route_endpoint_gap").assertDoesNotExist()
        compose.waitUntil(5_000) {
            var cleared = false
            compose.runOnIdle {
                cleared = !markerAt(roadEnd, "hmi-driving-endpoint-dot") && markerAt(selected.point, "hmi-destination-dot")
            }
            cleared
        }
    }

    @Test fun alignedDestinationHasNoGapWarning() {
        val destination = selected.copy(point = roadEnd)
        val state = NavigationState(destination = destination, route = route(destination), phase = TripPhase.ROUTE_READY)
        compose.setContent { AutoHMITheme { NavigationPanel(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("route_endpoint_gap").assertDoesNotExist()
        compose.onNodeWithText("400 m · 2 min driving estimate").assertIsDisplayed()
    }
}
