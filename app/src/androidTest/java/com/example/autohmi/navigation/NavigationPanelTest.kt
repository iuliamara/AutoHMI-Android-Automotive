package com.example.autohmi.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.example.autohmi.createDisplayContentRule as createComposeRule
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.theme.AutoHMITheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun deniedAndApproximatePermissionsHaveContextualActions() {
        val state = mutableStateOf(NavigationState(location = LocationState(status = LocationStatus.DENIED)))
        var requests = 0; var settings = 0
        compose.setContent { AutoHMITheme {
            NavigationPanel(state.value, { requests++ }, { settings++ }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Enable location").performClick()
        compose.runOnIdle { assertEquals(1, requests); state.value = state.value.copy(location = LocationState(LocationPermission.APPROXIMATE, LocationStatus.AVAILABLE)) }
        compose.onNodeWithText("Improve location accuracy").performClick()
        compose.runOnIdle { assertEquals(2, requests) }
        compose.onNodeWithText("Approximate position.", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(location = LocationState(LocationPermission.PRECISE, LocationStatus.SERVICES_DISABLED)) }
        compose.onNodeWithText("Open location settings").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }
    @Test fun coordinateValidationCancellationAndDestinationChangesAreExplicit() {
        val state = mutableStateOf(NavigationState())
        var selections = 0
        compose.setContent { AutoHMITheme {
            Layout(state.value, onSelect = { point, name -> selections++; state.value = state.value.copy(destination = Destination(point, name.orEmpty().ifBlank { "Pinned destination" }), phase = TripPhase.DESTINATION_SELECTED) })
        } }
        compose.onNodeWithText("Set destination").performClick()
        compose.onNodeWithTag("destination_latitude").performTextInput("91")
        compose.onNodeWithTag("destination_longitude").performTextInput("13")
        compose.onNodeWithText("Use destination").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, selections) }
        compose.onNodeWithText("Set destination").performClick()
        compose.onNodeWithTag("destination_latitude").performTextInput("52.52")
        compose.onNodeWithTag("destination_longitude").performTextInput("13.405")
        compose.onNodeWithText("Use destination").performClick()
        compose.onNodeWithText("Pinned destination").assertIsDisplayed()
        compose.onNodeWithText("Plan route").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, selections) }
    }
    @Test fun realRouteStateEnablesTripAndStaleStateDoesNotInventEstimates() {
        val origin = GeoPoint(52.52, 13.405); val destination = Destination(GeoPoint(52.522, 13.409), "Offline UI fixture")
        val route = RoutePlan(origin, destination, listOf(origin, GeoPoint(52.52, 13.409), destination.point), 600.0, 90.0, "Offline UI fixture only")
        val state = mutableStateOf(NavigationState(location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(origin, 5.0, 1000)),
            phase = TripPhase.ROUTE_READY, destination = destination, route = route, routingConfigured = true))
        var starts = 0; var ends = 0
        compose.setContent { AutoHMITheme {
            NavigationPanel(state.value, {}, {}, {}, {}, {}, {}, { starts++ }, { ends++ }, {})
        } }
        compose.onNodeWithText("Start trip").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            state.value = state.value.copy(phase = TripPhase.TRIP_ACTIVE, issue = NavigationIssue.STALE_LOCATION)
        }
        compose.onNodeWithText("Location is stale.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("End trip").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, ends) }
        compose.onAllNodesWithText("Estimated arrival", substring = true).assertCountEquals(0)
    }
    @Test fun mapFailuresAreRecoverableAndFollowAndThemeControlsAreExplicit() {
        val state = mutableStateOf(NavigationState(location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE,
            LocationFix(GeoPoint(52.52, 13.405), 5.0, 1000))))
        var recentered = 0; var followed = 0
        compose.setContent { AutoHMITheme {
            NavigationLayout(state.value, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, { recentered++ }, {}, { followed++ },
                { state.value = state.value.copy(theme = MapTheme.LIGHT) }, {},
                map = { modifier, retry, status -> Box(modifier) { SideEffect { status(if (retry == 0) MapLoadStatus.ERROR else MapLoadStatus.READY) } } })
        } }
        compose.onNodeWithText("Retry map").performClick()
        compose.onAllNodesWithText("Map unavailable.", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Recenter").performClick()
        compose.onNodeWithText("Following").performClick()
        compose.onNodeWithText("Day map").performClick()
        compose.onNodeWithText("Night map").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, recentered); assertEquals(1, followed) }
    }
    @Composable private fun Layout(state: NavigationState, onSelect: (GeoPoint, String?) -> Unit) {
        NavigationLayout(state, {}, {}, {}, onSelect, {}, {}, {}, {}, {}, {}, {}, {}, {},
            map = { modifier, _, status -> Box(modifier) { SideEffect { status(MapLoadStatus.READY) } } })
    }
}
