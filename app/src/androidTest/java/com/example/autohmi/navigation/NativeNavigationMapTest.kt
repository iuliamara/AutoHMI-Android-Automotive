package com.example.autohmi.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import com.example.autohmi.createDisplayContentRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView

class NativeNavigationMapTest {
    @get:Rule val compose = createComposeRule()
    private val point = GeoPoint(52.52, 13.405) // Synthetic Berlin test scene; not device GPS.
    @Test fun zoomButtonsMoveTheNativeCameraAndKeepZoomThroughGpsAndRecomposition() {
        val status = AtomicReference(MapLoadStatus.LOADING)
        val camera = AtomicReference<MapCamera?>(null)
        val fix = LocationFix(point, 5.0, 1000, simulated = true)
        val state = mutableStateOf(NavigationState(location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, fix)))
        compose.setContent { AutoHMITheme {
            NavigationMap(state.value, MapCamera(point, 16.0), MapMode.OFFLINE_TEST, 0, { _, _ -> },
                { state.value = state.value.copy(followLocation = false) }, { camera.set(it) }, { status.set(it) },
                Modifier.fillMaxSize().testTag("native_test_map"))
        } }
        compose.waitUntil(30_000) { status.get() == MapLoadStatus.READY }
        val mapBounds = compose.onNodeWithTag("native_test_map").fetchSemanticsNode().boundsInRoot
        for (label in listOf("Zoom in", "Zoom out")) {
            val button = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(button.left >= mapBounds.left && button.top >= mapBounds.top && button.right <= mapBounds.right && button.bottom <= mapBounds.bottom)
        }
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitUntil(5_000) { camera.get()?.let { kotlin.math.abs(it.zoom - 17.0) < 0.02 } == true }
        repeat(3) { compose.onNodeWithContentDescription("Zoom out").performClick() }
        compose.waitUntil(5_000) { camera.get()?.let { kotlin.math.abs(it.zoom - 14.0) < 0.02 } == true }
        val next = fix.copy(point = GeoPoint(52.5201, 13.4051), elapsedMs = 2000)
        compose.runOnIdle { state.value = state.value.copy(location = state.value.location.copy(fix = next)) }
        compose.waitUntil(5_000) { camera.get()?.let { GeoMath.distance(it.center, next.point) < 2 && kotlin.math.abs(it.zoom - 14.0) < 0.02 } == true }
        compose.runOnIdle { state.value = state.value.copy(theme = MapTheme.LIGHT) }
        compose.waitForIdle()
        compose.waitUntil(15_000) { status.get() == MapLoadStatus.READY }
        assertEquals(14.0, requireNotNull(camera.get()).zoom, 0.02)
    }

    @Test fun offlineNativeMapRendersHandlesGesturesKeepsCameraAndDisposes() {
        val status = AtomicReference(MapLoadStatus.LOADING)
        val camera = AtomicReference<MapCamera?>(null)
        val gestures = AtomicInteger(); val pins = AtomicInteger()
        val state = mutableStateOf(NavigationState(followLocation = false))
        val visible = mutableStateOf(true)
        compose.setContent { AutoHMITheme {
            Column(Modifier.fillMaxSize()) {
                Text("Offline renderer test · synthetic scene")
                if (visible.value) NavigationMap(state.value, MapCamera(point, 13.0), MapMode.OFFLINE_TEST, 0,
                    { _, _ -> pins.incrementAndGet() }, { gestures.incrementAndGet() }, { camera.set(it) }, { status.set(it) },
                    Modifier.weight(1f).fillMaxWidth().testTag("native_test_map"))
                else Text("Map disposed")
            }
        } }
        compose.waitUntil(30_000) { status.get() == MapLoadStatus.READY }
        onView(isAssignableFrom(MapView::class.java)).perform(ViewActions.longClick())
        compose.waitUntil(5_000) { pins.get() > 0 }
        onView(isAssignableFrom(MapView::class.java)).perform(ViewActions.swipeLeft())
        compose.waitUntil(10_000) { gestures.get() > 0 && camera.get()?.let { GeoMath.distance(it.center, point) > 1 } == true }
        val saved = requireNotNull(camera.get())
        compose.runOnIdle { state.value = state.value.copy(theme = MapTheme.LIGHT) }
        compose.waitForIdle()
        compose.waitUntil(15_000) { status.get() == MapLoadStatus.READY }
        assertTrue(GeoMath.distance(saved.center, requireNotNull(camera.get()).center) < 5)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Map disposed").assertIsDisplayed()
    }

    @Test fun optionalPublicStylesRenderWithoutReadingGpsOrCallingRouting() {
        assumeTrue("Opt-in public map smoke test", InstrumentationRegistry.getArguments().getString("liveMapSmoke") == "true")
        val status = AtomicReference(MapLoadStatus.LOADING)
        val state = mutableStateOf(NavigationState(
            location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(point, 12.0, 1000, simulated = true)),
            destination = Destination(GeoPoint(52.522, 13.409), "Synthetic destination"), phase = TripPhase.DESTINATION_SELECTED, followLocation = false))
        compose.setContent { AutoHMITheme {
            Column(Modifier.fillMaxSize()) {
                Text("Public map smoke test · synthetic points · no route", modifier = Modifier.testTag("map_smoke_label"))
                NavigationLayout(state.value, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {},
                    { state.value = state.value.copy(theme = if (state.value.theme == MapTheme.DARK) MapTheme.LIGHT else MapTheme.DARK) }, {},
                    map = { modifier, retry, callback ->
                        NavigationMap(state.value, MapCamera(point, 14.5), MapMode.LIVE, retry, { _, _ -> }, {}, {},
                            { status.set(it); callback(it) }, modifier)
                    })
            }
        } }
        compose.waitUntil(60_000) { status.get() == MapLoadStatus.READY }
        capture("autohmi-map-smoke-dark.png")
        compose.onNodeWithText("Day map").performClick()
        compose.waitForIdle()
        compose.waitUntil(60_000) { status.get() == MapLoadStatus.READY }
        compose.onNodeWithText("Night map").assertIsDisplayed()
        capture("autohmi-map-smoke-light.png")
    }
    private fun capture(name: String) {
        compose.onNodeWithTag("map_smoke_label").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(File(instrumentation.targetContext.cacheDir, name)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
