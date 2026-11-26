package com.example.autohmi.navigation

import android.graphics.Bitmap
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView

/** Synthetic states and native offline style only; no real GPS, routing or account access. */
class NavigationSpeedometerTest {
    @get:Rule val compose = createDisplayContentRule()
    private val origin = GeoPoint(52.52, 13.405)
    private val destination = Destination(GeoPoint(52.521, 13.405), "Offline HUD fixture")
    private fun runningState() = NavigationState(
        route = RoutePlan(origin, destination, listOf(origin, destination.point), 120.0, 60.0), destination = destination,
        phase = TripPhase.TRIP_ACTIVE, routingConfigured = true,
        location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000, true)),
        demoDrive = DemoDriveState(origin, 0.0, 111.0)
    )

    @Test fun persistentHudSwitchesBetweenDemoGpsAndUnavailableSpeedWithoutAnActiveRoute() {
        val model = mutableStateOf(NavigationState())
        compose.setContent { AutoHMITheme {
            NavigationLayout(model.value, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {},
                map = { modifier, _, status -> Box(modifier) { SideEffect { status(MapLoadStatus.READY) } } })
        } }
        fun assertSpeed(source: String, number: String, speed: Float?) {
            compose.onNodeWithTag("demo_speedometer").assertIsDisplayed()
            compose.onNodeWithTag("demo_speed_value", useUnmergedTree = true).assertTextEquals(number)
            compose.onNodeWithTag("demo_speed_label", useUnmergedTree = true).assertTextEquals(source)
            compose.onNodeWithTag("demo_speedometer").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, if (speed == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(speed, 0f..120f)))
        }
        assertNull(model.value.route)
        assertSpeed("GPS", "--", null)
        compose.runOnIdle { model.value = model.value.copy(gpsSpeedKmh = 36.0) }
        assertSpeed("GPS", "36", 36f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { model.value = model.value.copy(demoDrive = DemoDriveState(origin, 0.0, 100.0)) }
        compose.mainClock.advanceTimeByFrame()
        assertSpeed("DEMO", "30", 30f)
        for (status in listOf(DemoDriveStatus.PAUSED, DemoDriveStatus.RUNNING, DemoDriveStatus.ARRIVED)) {
            compose.runOnIdle { model.value = model.value.copy(demoDrive = requireNotNull(model.value.demoDrive).copy(status = status)) }
            compose.mainClock.advanceTimeByFrame()
            // The 350 ms arc transition must not delay the actual speed text or semantics.
            assertSpeed("DEMO", if (status == DemoDriveStatus.RUNNING) "30" else "0", if (status == DemoDriveStatus.RUNNING) 30f else 0f)
            compose.onNodeWithTag("demo_speedometer").assertHasNoClickAction()
        }
        compose.runOnIdle { model.value = model.value.copy(demoDrive = null, phase = TripPhase.ROUTE_READY) }
        compose.mainClock.advanceTimeByFrame()
        assertSpeed("GPS", "36", 36f)
        compose.runOnIdle { model.value = model.value.copy(gpsSpeedKmh = null) }
        compose.mainClock.advanceTimeByFrame()
        assertSpeed("GPS", "--", null)
        compose.mainClock.autoAdvance = true
    }

    @Test fun androidLocationRetainsReportedSpeedFlagsWithoutReadingDeviceGps() {
        val raw = Location("gps").apply {
            latitude = origin.latitude; longitude = origin.longitude
            accuracy = 5f; elapsedRealtimeNanos = 1_000_000_000L
        }
        assertNull(requireNotNull(raw.toNavigationFix()).speedMetersPerSecond)
        raw.speed = 0f
        assertEquals(0.0, requireNotNull(raw.toNavigationFix()?.speedMetersPerSecond), 0.0)
        raw.speed = 10f; raw.speedAccuracyMetersPerSecond = 0.5f
        val fix = requireNotNull(raw.toNavigationFix())
        assertEquals(10.0, requireNotNull(fix.speedMetersPerSecond), 0.0)
        assertEquals(0.5, requireNotNull(fix.speedAccuracyMetersPerSecond), 0.0)
        assertEquals(36.0, requireNotNull(LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, fix).validGpsSpeedKmh(1000)), 0.0)
        raw.speedAccuracyMetersPerSecond = 10f
        assertEquals(origin, requireNotNull(raw.toNavigationFix()).point)
        assertNull(LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, raw.toNavigationFix()).validGpsSpeedKmh(1000))
        raw.removeSpeed()
        assertNull(requireNotNull(raw.toNavigationFix()).speedMetersPerSecond)
    }

    @Test fun topLeftHudFitsSafeBoundsAtNormalAndLargeTextAndKeepsNativeCameraControls() {
        val model = mutableStateOf(runningState())
        val short = mutableStateOf(false)
        val narrow = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        val loaded = AtomicReference(MapLoadStatus.LOADING)
        val camera = AtomicReference<MapCamera?>(null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) { AutoHMITheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                        Box(Modifier.width(if (narrow.value) 800.dp else 1100.dp).height(if (short.value) 360.dp else 560.dp).testTag("hud_safe_scene")) {
                            NavigationLayout(model.value, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {},
                                map = { modifier, retry, status -> NavigationMap(model.value, MapCamera(origin, 15.0), MapMode.OFFLINE_TEST,
                                    retry, { _, _ -> }, { model.value = model.value.copy(followLocation = false) }, { camera.set(it) },
                                    { loaded.set(it); status(it) }, modifier) })
                        }
                    }
                }
            } }
        }
        compose.waitUntil(20_000) { loaded.get() == MapLoadStatus.READY }
        for (scene in listOf("gps-normal", "demo-large", "gps-compact", "gps-narrow")) {
            compose.runOnIdle {
                short.value = scene.endsWith("compact") || scene.endsWith("narrow"); narrow.value = scene.endsWith("narrow")
                fontScale.value = if (scene == "gps-normal") 1f else 1.3f
                model.value = if (scene == "demo-large") runningState() else runningState().copy(
                    demoDrive = null, phase = TripPhase.IDLE, route = null, destination = null, gpsSpeedKmh = 36.0)
            }
            compose.waitForIdle()
            val hud = compose.onNodeWithTag("demo_speedometer").assertIsDisplayed()
            val dial = hud.fetchSemanticsNode().boundsInRoot
            val map = compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot
            assertEquals("Dial must stay circular", dial.width, dial.height, 0.5f)
            assertTrue(dial.left >= map.left && dial.top >= map.top && dial.right <= map.right && dial.bottom <= map.bottom)
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("HUD must sit near the map's upper-left", dial.left - map.left <= 20f * density && dial.top - map.top <= 20f * density)
            assertFalse("HUD must not overlap the navigation information panel", dial.overlaps(compose.onNodeWithTag("navigation_panel").fetchSemanticsNode().boundsInRoot))
            for (control in listOf(
                compose.onNodeWithText("Recenter"), compose.onNodeWithText("Overview"), compose.onNodeWithText("Following"),
                compose.onNodeWithContentDescription("Zoom in"), compose.onNodeWithContentDescription("Zoom out")
            )) assertFalse("HUD must not overlap a map control", dial.overlaps(control.assertIsDisplayed().fetchSemanticsNode().boundsInRoot))
            for (label in listOf("Zoom in", "Zoom out")) {
                val zoom = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                assertTrue("Zoom must remain bottom-right", zoom.top > map.center.y && zoom.left > map.center.x)
            }
            if (InstrumentationRegistry.getArguments().getString("demoHudScreenshot") == "true") {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val name = "navigation-speedometer-$scene.png"
                File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            for (tag in listOf("demo_speed_value", "demo_speed_label", "demo_speed_unit")) {
                val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(action(layouts))
                    val layout = layouts.single()
                    assertFalse("$tag overflow at 1.3x: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, size=${layout.size}, paragraph=${layout.multiParagraph.height}", layout.hasVisualOverflow)
                }
                val text = node.fetchSemanticsNode().boundsInRoot
                for (x in listOf(text.left, text.right)) for (y in listOf(text.top, text.bottom)) {
                    val dx = x - dial.center.x; val dy = y - dial.center.y
                    assertTrue("$tag must fit inside the circular clip", dx * dx + dy * dy <= dial.width * dial.width / 4 + 1)
                }
            }
        }
        // Use the project's native-View input path, in clear map space below the upper HUD/controls.
        compose.runOnIdle { short.value = false; narrow.value = false }
        compose.waitForIdle()
        compose.waitUntil(5_000) { camera.get() != null }
        val previousZoom = requireNotNull(camera.get()).zoom
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitUntil(5_000) { (camera.get()?.zoom ?: 0.0) > previousZoom + 0.9 }
        onView(isAssignableFrom(MapView::class.java)).perform(ViewActions.swipeLeft())
        compose.waitUntil(5_000) { !model.value.followLocation }
    }
}
