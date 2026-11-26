package com.example.autohmi.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.navigation.ui.MapLoadStatus
import com.example.autohmi.navigation.ui.NavigationLayout
import com.example.autohmi.navigation.ui.NavigationPanel
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DemoDrivePanelTest {
    @get:Rule val compose = createDisplayContentRule()
    private val origin = GeoPoint(52.52, 13.405)
    private val destination = Destination(GeoPoint(52.521, 13.405), "Offline DEMO fixture")
    private val route = RoutePlan(origin, destination, listOf(origin, destination.point), 120.0, 60.0)
    @Test fun aReadyRouteOffersExplicitDemoAndRetainsRealStartTrip() {
        var demos = 0; var realStarts = 0
        val state = NavigationState(route = route, destination = destination, phase = TripPhase.ROUTE_READY,
            location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000)))
        compose.setContent { AutoHMITheme {
            NavigationPanel(state, {}, {}, {}, {}, {}, {}, { realStarts++ }, {}, {}, onSimulate = { demos++ })
        } }
        compose.onNodeWithText("Simulate drive (DEMO)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Start trip").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, demos); assertEquals(1, realStarts) }
    }
    @Test fun demoIsClearlyLabeledAndCanBeStoppedWithoutEnablingRecalculation() {
        var stops = 0
        val state = NavigationState(route = route, destination = destination, phase = TripPhase.TRIP_ACTIVE,
            location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000)),
            demoDrive = DemoDriveState(GeoPoint(52.5205, 13.405), 50.0, 100.0))
        compose.setContent { AutoHMITheme {
            NavigationPanel(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onStopSimulation = { stops++ })
        } }
        compose.onNodeWithText("DEMO · Simulated drive · 30 km/h").assertIsDisplayed()
        compose.onNodeWithText("DEMO position:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Device GPS position:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Recalculate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Start trip").assertDoesNotExist()
        compose.onNodeWithText("Pause DEMO").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }
    @Test fun anActiveGpsTripOffersAnExplicitSwitchToDemoWithoutReplanning() {
        var demos = 0
        val state = NavigationState(route = route, destination = destination, phase = TripPhase.TRIP_ACTIVE,
            routingConfigured = true, location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000)))
        compose.setContent { AutoHMITheme {
            NavigationPanel(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onSimulate = { demos++ })
        } }
        compose.onNodeWithText("Trip active · GPS progress").assertIsDisplayed()
        compose.onNodeWithText("Simulate drive (DEMO)").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, demos) }
    }
    @Test fun pausedDemoKeepsItsPositionAndOffersSeparateResumeAndExitActions() {
        var resumes = 0; var exits = 0
        val state = NavigationState(route = route, destination = destination, phase = TripPhase.TRIP_ACTIVE,
            routingConfigured = true, remainingMeters = 60.0,
            location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000)),
            demoDrive = DemoDriveState(GeoPoint(52.5205, 13.405), 50.0, 100.0, DemoDriveStatus.PAUSED))
        compose.setContent { AutoHMITheme {
            NavigationPanel(state, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onResumeSimulation = { resumes++ }, onExitDemo = { exits++ })
        } }
        compose.onNodeWithText("DEMO paused").assertIsDisplayed()
        compose.onNodeWithText("DEMO position: 52.52050, 13.40500").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("DEMO: 60 m remaining driving distance · paused").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("DEMO arrival", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Simulate drive (DEMO)").assertDoesNotExist()
        compose.onNodeWithText("Pause DEMO").assertDoesNotExist()
        compose.onNodeWithText("Resume DEMO").assertIsDisplayed().performClick()
        compose.onNodeWithText("Exit DEMO").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(1, exits) }
    }
    @Test fun completedDemoShowsDestinationReachedZeroDistanceAndNoPendingEta() {
        var exits = 0
        val selected = Destination(GeoPoint(52.522, 13.405), "Original selected destination")
        val state = NavigationState(route = route.copy(destination = selected), destination = selected, phase = TripPhase.ARRIVED,
            routingConfigured = true, remainingMeters = 0.0, remainingSeconds = 0.0, estimatedArrivalEpochMs = 1_700_000_000_000,
            demoDrive = DemoDriveState(destination.point, 100.0, 100.0, DemoDriveStatus.ARRIVED))
        compose.setContent { AutoHMITheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                    Box(Modifier.width(350.dp).height(360.dp).testTag("demo_arrival_safe_area")) {
                        NavigationPanel(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize(), onExitDemo = { exits++ })
                    }
                }
            }
        } }
        compose.onNodeWithTag("demo_arrival_confirmation").assertIsDisplayed()
        compose.onNodeWithText("DEMO · Driving route complete").assertIsDisplayed()
        compose.onNodeWithText("Destination reached").assertIsDisplayed()
        compose.onNodeWithText("0 m remaining driving distance").assertIsDisplayed()
        compose.onNodeWithText("Selected destination is approximately", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Completed driving route · 120 m total").assertExists()
        compose.onAllNodesWithText("DEMO arrival", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("driving estimate", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("0 min estimated", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Resume DEMO").assertDoesNotExist()
        compose.onNodeWithText("Pause DEMO").assertDoesNotExist()
        val safeArea = compose.onNodeWithTag("demo_arrival_safe_area").fetchSemanticsNode().boundsInRoot
        val exit = compose.onNodeWithText("Exit DEMO").assertIsDisplayed()
        val bounds = exit.fetchSemanticsNode().boundsInRoot
        assertTrue("Exit DEMO must remain within the safe content area", bounds.left >= safeArea.left && bounds.top >= safeArea.top &&
            bounds.right <= safeArea.right && bounds.bottom <= safeArea.bottom)
        exit.performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }
    @Test fun readyRouteDemoIsVisibleAndClickableWithoutScrollingInAShortAutomotivePanel() {
        var demos = 0
        val selected = Destination(GeoPoint(52.522, 13.405), "Synthetic destination beyond the driving endpoint")
        val state = NavigationState(route = route.copy(destination = selected), destination = selected, phase = TripPhase.ROUTE_READY,
            routingConfigured = true, issue = NavigationIssue.STALE_LOCATION,
            location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000)))
        compose.setContent { AutoHMITheme {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                    Box(Modifier.width(1000.dp).height(360.dp).testTag("demo_safe_area")) {
                        NavigationLayout(state, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {},
                            map = { modifier, _, status -> Box(modifier) {
                                Text("Offline map fixture · synthetic route · no GPS or routing requests")
                                SideEffect { status(MapLoadStatus.READY) }
                            } }, onSimulate = { demos++ })
                    }
                }
            }
        } }
        val action = compose.onNodeWithText("Simulate drive (DEMO)").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val safeArea = compose.onNodeWithTag("demo_safe_area").fetchSemanticsNode().boundsInRoot
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("DEMO action must fit inside the safe content area", bounds.left >= safeArea.left && bounds.top >= safeArea.top &&
            bounds.right <= safeArea.right && bounds.bottom <= safeArea.bottom)
        if (InstrumentationRegistry.getArguments().getString("demoVisibilityScreenshot") == "true") {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "demo-button-ready.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        // Details must scroll independently: even their final actions cannot hide DEMO.
        compose.onNodeWithTag("navigation_panel_details").performScrollToNode(hasText("Clear destination"))
        action.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, demos) }
    }
}
