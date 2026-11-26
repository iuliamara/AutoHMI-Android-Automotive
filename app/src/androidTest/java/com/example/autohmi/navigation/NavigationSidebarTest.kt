package com.example.autohmi.navigation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.HistoryState
import com.example.autohmi.VehicleStatus
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.VehicleApp
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapView

/** Offline, synthetic fixtures only: no GPS, route requests, Spotify session or database. */
class NavigationSidebarTest {
    @get:Rule val compose = createDisplayContentRule()
    private val origin = GeoPoint(52.52, 13.405)
    private val destination = Destination(GeoPoint(52.521, 13.406), "Sidebar fixture")
    private fun state() = NavigationState(
        destination = destination, route = RoutePlan(origin, destination, listOf(origin, destination.point), 130.0, 60.0),
        location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000, true)),
        phase = TripPhase.TRIP_ACTIVE, routingConfigured = true, followLocation = false,
        demoDrive = DemoDriveState(origin, 25.0, 130.0), remainingMeters = 105.0, remainingSeconds = 12.6)

    @Composable private fun Fixture(model: NavigationState, map: @Composable (Modifier, Int, (MapLoadStatus) -> Unit) -> Unit,
                                    onArrivalRadius: () -> Unit = {}, onManual: () -> Unit = {}) {
        NavigationLayout(model, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, onManual, {}, onArrivalRadius, map = map)
    }

    private fun collapsed(value: Boolean) {
        compose.onNodeWithTag("navigation_sidebar_handle").assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (value) "Collapsed" else "Expanded"))
        compose.waitForIdle()
    }

    @Test fun swipesButtonsScrollAndSavedStatePreserveNavigation() {
        val model = mutableStateOf(state())
        val restore = StateRestorationTester(compose)
        var mounts = 0
        var disposals = 0
        var arrivalClicks = 0
        restore.setContent { AutoHMITheme {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Fixture(model.value, map = { modifier, _, status ->
                    Box(modifier) {
                        DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                        SideEffect { status(MapLoadStatus.READY) }
                    }
                }, onArrivalRadius = { arrivalClicks++ })
            }
        } }
        collapsed(false)
        // Bottom controls remain reachable; vertical scroll and taps must not hide the panel.
        compose.onNodeWithText("Arrival: 35 m").performScrollTo().performClick()
        assertEquals(1, arrivalClicks)
        collapsed(false)
        val scrollBefore = compose.onNodeWithTag("navigation_panel_details").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(scrollBefore > 0f)
        val mapWidth = compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("navigation_panel_details").performTouchInput {
            swipe(Offset(width * .85f, height * .6f), Offset(width * .15f, height * .6f), 450)
        }
        collapsed(true)
        assertTrue(compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.width > mapWidth + 200)
        compose.onNodeWithTag("navigation_map").performTouchInput {
            swipe(Offset(width * .4f, height * .5f), Offset(width * .8f, height * .5f), 450)
        }
        collapsed(true) // Outside 28 dp: ordinary map-area gestures do not expand.
        val edge = compose.onNodeWithTag("navigation_sidebar_edge_zone").assertWidthIsEqualTo(28.dp)
        edge.performTouchInput { swipe(Offset(12f, height * .35f), Offset(140f, height * .35f), 450) }
        collapsed(false)
        assertEquals(scrollBefore, compose.onNodeWithTag("navigation_panel_details").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        assertEquals(1, mounts)
        assertEquals(0, disposals)
        assertEquals(state(), model.value)
        compose.onNodeWithContentDescription("Hide navigation information").performClick()
        collapsed(true)
        compose.runOnIdle { model.value = model.value.copy(remainingMeters = 90.0, demoDrive = model.value.demoDrive?.copy(traveledMeters = 40.0)) }
        restore.emulateSavedInstanceStateRestore()
        collapsed(true)
        compose.onNodeWithContentDescription("Show navigation information").performClick()
        collapsed(false)
        assertEquals(40.0, requireNotNull(model.value.demoDrive).traveledMeters, 0.0)
        assertEquals(state().route, model.value.route)
        assertEquals(state().location, model.value.location)
        assertFalse(model.value.followLocation)
    }

    @Test fun handleHudAndControlsFitAtNormalAndLargeFontsIncludingShortWindows() {
        val font = mutableStateOf(1f)
        val short = mutableStateOf(false)
        val arrived = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) { AutoHMITheme {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.width(1100.dp).height(if (short.value) 360.dp else 560.dp).testTag("sidebar_safe_area")) {
                        val model = if (!arrived.value) state() else state().copy(phase = TripPhase.ARRIVED,
                            demoDrive = DemoDriveState(destination.point, 130.0, 130.0, DemoDriveStatus.ARRIVED),
                            remainingMeters = 0.0, remainingSeconds = null,
                            destination = Destination(GeoPoint(52.522, 13.406), "Selected point beyond road"),
                            route = state().route?.copy(destination = Destination(GeoPoint(52.522, 13.406), "Selected point beyond road")))
                        Fixture(model, map = { modifier, _, status -> Box(modifier) { SideEffect { status(MapLoadStatus.READY) } } })
                    }
                }
            } }
        }
        for ((scale, isShort, isArrived) in listOf(Triple(1f, false, false), Triple(1.3f, false, false),
            Triple(1.3f, true, false), Triple(1.3f, true, true))) {
            compose.runOnIdle { font.value = scale; short.value = isShort; arrived.value = isArrived }
            for (hide in listOf(false, true)) {
                if (hide) compose.onNodeWithTag("navigation_sidebar_handle").performClick()
                collapsed(hide)
                val map = compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot
                val hud = compose.onNodeWithTag("demo_speedometer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val handle = compose.onNodeWithTag("navigation_sidebar_handle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(map.contains(hud.topLeft) && map.contains(hud.bottomRight - Offset(.1f, .1f)))
                assertTrue(map.contains(handle.topLeft) && map.contains(handle.bottomRight - Offset(.1f, .1f)))
                assertFalse(hud.overlaps(handle))
                assertTrue(hud.left - map.left < 20 * compose.activity.resources.displayMetrics.density)
                for (label in listOf("Recenter", "Overview", "Follow location")) {
                    val control = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertFalse(handle.overlaps(control))
                    assertFalse(hud.overlaps(control))
                }
                if (!hide) {
                    compose.onNodeWithText("Clear destination").performScrollTo().assertIsDisplayed()
                    if (isArrived) compose.onNodeWithTag("exit_demo_action").performScrollTo().assertIsDisplayed()
                }
                screenshot("sidebar-${if (hide) "collapsed" else "expanded"}-$scale-$isShort-$isArrived.png")
            }
            compose.onNodeWithTag("navigation_sidebar_handle").performClick()
        }
    }

    @Test fun mountedNativeMapRetainsZoomCameraDemoAndApplicationRail() {
        val model = state()
        val loaded = AtomicReference(MapLoadStatus.LOADING)
        var map: MapView? = null
        compose.setContent { AutoHMITheme {
            VehicleApp(VehicleStatus(), HistoryState(), true, false, false, remember { SnackbarHostState() },
                {}, {}, {}, {}, {}, {}, {}, navigationContent = {
                    Fixture(model, map = { modifier, retry, status ->
                        NavigationMap(model, MapCamera(origin, 16.0), MapMode.OFFLINE_TEST, retry, { _, _ -> }, {}, {},
                            { loaded.set(it); status(it) }, modifier)
                    })
                })
        } }
        compose.onNodeWithText("Navigate").performClick()
        compose.waitUntil(20_000) { loaded.get() == MapLoadStatus.READY }
        compose.onNodeWithTag("navigation_rail").assertIsDisplayed()
        fun camera(): CameraPosition {
            val value = AtomicReference<CameraPosition>()
            onView(isAssignableFrom(MapView::class.java)).perform(object : ViewAction {
                override fun getConstraints() = isAssignableFrom(MapView::class.java)
                override fun getDescription() = "Read the mounted offline map and camera"
                override fun perform(ui: UiController, view: View) {
                    if (map == null) map = view as MapView else assertSame(map, view)
                    (view as MapView).getMapAsync { value.set(it.cameraPosition) }
                    ui.loopMainThreadUntilIdle()
                }
            })
            return requireNotNull(value.get())
        }
        fun settledCamera(): CameraPosition {
            var last = camera()
            var unchangedSince = SystemClock.uptimeMillis()
            compose.waitUntil(8_000) {
                val next = camera()
                if (next != last) { last = next; unchangedSince = SystemClock.uptimeMillis() }
                SystemClock.uptimeMillis() - unchangedSince >= 400
            }
            return last
        }
        val initial = camera()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitUntil(5_000) { camera().zoom > initial.zoom + .99 }
        val explored = settledCamera()
        compose.onNodeWithContentDescription("Hide navigation information").performClick()
        collapsed(true)
        compose.onNodeWithTag("navigation_rail").assertIsDisplayed()
        assertEquals(explored.zoom, settledCamera().zoom, .02)
        onView(isAssignableFrom(MapView::class.java)).perform(ViewActions.swipeLeft())
        collapsed(true) // A native map swipe outside the edge never reopens the panel.
        val panned = settledCamera()
        assertNotEquals(explored.target, panned.target)
        compose.onNodeWithContentDescription("Show navigation information").performClick()
        collapsed(false)
        val restored = settledCamera()
        assertEquals(panned.zoom, restored.zoom, .02)
        assertEquals(panned.target!!.latitude, restored.target!!.latitude, .0001)
        assertEquals(panned.target!!.longitude, restored.target!!.longitude, .0001)
        compose.onNodeWithTag("navigation_rail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom out").assertIsDisplayed().assertHasClickAction()
        assertEquals(25.0, requireNotNull(model.demoDrive).traveledMeters, 0.0)
        screenshot("sidebar-native-expanded.png")
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("sidebarScreenshot") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
