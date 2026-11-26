package com.example.autohmi.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.HistoryState
import com.example.autohmi.VehicleStatus
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.VehicleApp
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic route/progress and offline tiles; no real GPS, routing requests or account access. */
class NavigationInformationUiTest {
    @get:Rule val compose = createDisplayContentRule()
    private val origin = GeoPoint(52.52, 13.405)
    private val end = GeoPoint(52.521, 13.405)
    private fun demo() = NavigationState(route = RoutePlan(origin, Destination(end), listOf(origin, end), 120.0, 60.0),
        destination = Destination(end), routingConfigured = true, phase = TripPhase.TRIP_ACTIVE, followLocation = false,
        location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000, true)),
        demoDrive = DemoDriveState(origin, 40.0, 120.0), remainingMeters = 80.0, remainingSeconds = 9.6,
        estimatedArrivalEpochMs = 1_700_000_000_000L)

    @Composable private fun Scene(state: NavigationState, native: Boolean = false, ready: (MapLoadStatus) -> Unit = {},
                                  onArrivalRadius: () -> Unit = {}) {
        NavigationLayout(state, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, onArrivalRadius,
            map = { modifier, retry, status ->
                if (native) NavigationMap(state, MapCamera(origin, 16.0), MapMode.OFFLINE_TEST, retry,
                    { _, _ -> }, {}, {}, { ready(it); status(it) }, modifier)
                else Box(modifier) { SideEffect { status(MapLoadStatus.READY) } }
            })
    }

    @Test fun collapsedSummaryReactsToDemoGpsPauseArrivalAndMissingRoute() {
        val model = mutableStateOf(demo())
        compose.setContent { AutoHMITheme {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { Scene(model.value) }
        } }
        compose.onNodeWithTag("collapsed_navigation_info").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide navigation information").performClick()
        compose.onNodeWithTag("collapsed_navigation_info").assertIsDisplayed()
        compose.onNodeWithTag("expanded_navigation_info").assertDoesNotExist()
        compose.onNodeWithTag("trip_info_status").assertTextEquals("DEMO · Driving")
        compose.onNodeWithTag("trip_info_distance").assertTextEquals("80 m remaining")
        fun eta() = "ETA ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(requireNotNull(model.value.estimatedArrivalEpochMs)))}"
        compose.onNodeWithTag("trip_info_eta").assertTextEquals(eta())
        compose.runOnIdle { model.value = model.value.copy(remainingMeters = 40.0, estimatedArrivalEpochMs = 1_700_000_120_000L,
            demoDrive = model.value.demoDrive?.copy(traveledMeters = 80.0)) }
        compose.onNodeWithTag("trip_info_distance").assertTextEquals("40 m remaining")
        compose.onNodeWithTag("trip_info_eta").assertTextEquals(eta())
        compose.runOnIdle { model.value = model.value.copy(demoDrive = model.value.demoDrive?.copy(status = DemoDriveStatus.PAUSED)) }
        compose.onNodeWithTag("trip_info_status").assertTextEquals("DEMO · Paused")
        compose.onNodeWithTag("trip_info_eta").assertTextEquals("Resume DEMO for ETA")
        compose.runOnIdle { model.value = model.value.copy(demoDrive = model.value.demoDrive?.copy(status = DemoDriveStatus.ARRIVED)) }
        compose.onNodeWithTag("trip_info_distance").assertTextEquals("0 m remaining")
        compose.onNodeWithTag("trip_info_eta").assertTextEquals("Driving endpoint reached")
        compose.runOnIdle { model.value = demo().copy(demoDrive = null) }
        compose.onNodeWithTag("trip_info_status").assertTextEquals("GPS · Navigating")
        compose.onNodeWithTag("trip_info_distance").assertTextEquals("80 m remaining")
        compose.onNodeWithTag("trip_info_eta").assertTextEquals(eta())
        compose.runOnIdle { model.value = model.value.copy(remainingMeters = null, estimatedArrivalEpochMs = null, issue = NavigationIssue.STALE_LOCATION) }
        compose.onNodeWithTag("trip_info_distance").assertTextEquals("Distance unavailable")
        compose.onNodeWithTag("trip_info_eta").assertTextEquals("ETA unavailable")
        compose.onNodeWithContentDescription("Show navigation information").performClick()
        compose.onNodeWithTag("collapsed_navigation_info").assertDoesNotExist()
        compose.runOnIdle { model.value = NavigationState() }
        compose.onNodeWithContentDescription("Hide navigation information").performClick()
        compose.onNodeWithTag("collapsed_navigation_info").assertDoesNotExist()
        compose.onNodeWithTag("demo_speedometer").assertIsDisplayed()
    }

    @Test fun automotiveSummaryAndSidebarFitAboveMusicAtNormalAndLargeText() {
        val font = mutableStateOf(1f)
        val model = mutableStateOf(demo())
        val loaded = AtomicReference(MapLoadStatus.LOADING)
        var arrivalClicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) { AutoHMITheme {
                VehicleApp(VehicleStatus(), HistoryState(), true, false, false, remember { SnackbarHostState() },
                    {}, {}, {}, {}, {}, {}, {}, navigationContent = {
                        Scene(model.value, native = true, ready = loaded::set, onArrivalRadius = { arrivalClicks++ })
                    })
            } }
        }
        compose.onNodeWithText("Navigate").performClick()
        compose.waitUntil(20_000) { loaded.get() == MapLoadStatus.READY }
        for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { font.value = scale }
            val bar = compose.onNodeWithTag("app_bottom_bar").fetchSemanticsNode().boundsInRoot
            val safe = compose.onNodeWithTag("navigation_content_safe_area").fetchSemanticsNode().boundsInRoot
            assertTrue(safe.bottom <= bar.top + 1f)
            compose.onNodeWithText("Clear destination").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bottom = compose.onNodeWithText("Arrival: 35 m").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertTrue(bottom.fetchSemanticsNode().boundsInRoot.bottom <= bar.top)
            bottom.performClick()
            compose.onNodeWithText("Road-route planning and GPS progress only. No turn-by-turn guidance.").performScrollTo().assertIsDisplayed()
            screenshot("navigation-info-expanded-$scale.png")
            compose.onNodeWithContentDescription("Hide navigation information").performClick()
            val overlay = compose.onNodeWithTag("collapsed_navigation_info").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(safe.contains(overlay.topLeft) && safe.contains(overlay.bottomRight - Offset(.1f, .1f)))
            for (tag in listOf("demo_speedometer", "navigation_map_controls", "navigation_sidebar_handle", "navigation_marker_legend")) {
                assertFalse("Summary overlaps $tag", overlay.overlaps(compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot))
            }
            for (label in listOf("Zoom in", "Zoom out")) {
                val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertFalse(overlay.overlaps(bounds))
                assertTrue(bounds.bottom <= bar.top)
            }
            for (tag in listOf("trip_info_status", "trip_info_distance", "trip_info_eta")) {
                compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("collapsed_navigation_info")), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(action(layouts))
                    assertTrue("$tag clips at scale $scale: ${layouts.map { "${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" }}",
                        layouts.all { !it.didOverflowWidth && !it.didOverflowHeight })
                }
            }
            screenshot("navigation-info-collapsed-$scale.png")
            compose.onNodeWithContentDescription("Show navigation information").performClick()
        }
        assertEquals(2, arrivalClicks)
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("navigationInfoScreenshot") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
