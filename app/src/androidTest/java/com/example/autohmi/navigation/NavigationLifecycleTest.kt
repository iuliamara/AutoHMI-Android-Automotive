package com.example.autohmi.navigation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import com.example.autohmi.createDisplayComposeRule as createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.example.autohmi.MainActivity
import com.example.autohmi.VehicleViewModel
import com.example.autohmi.music.MusicViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

class NavigationLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun navigationAndLandscapeRecreationKeepCameraMusicAndVehicleState() {
        compose.onNodeWithText("Accelerate +10").performClick()
        lateinit var vehicle: VehicleViewModel; lateinit var music: MusicViewModel; lateinit var navigation: NavigationViewModel
        var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val originalConfigurationOrientation = compose.activity.resources.configuration.orientation
        val target = GeoPoint(52.52, 13.405)
        compose.onNodeWithText("Navigate").performClick()
        compose.onNodeWithTag("navigation_screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity {
            vehicle = ViewModelProvider(it)[VehicleViewModel::class.java]
            music = ViewModelProvider(it)[MusicViewModel::class.java]
            navigation = ViewModelProvider(it)[NavigationViewModel::class.java]
            orientation = it.requestedOrientation
            navigation.controller.selectDestination(target, "Offline lifecycle fixture")
            navigation.controller.overview()
        }
        compose.waitUntil(15_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { ready = GeoMath.distance(navigation.camera.center, target) < 5 }
            ready
        }
        // Gesture dispatch is covered separately by NativeNavigationMapTest. Move the
        // real native camera here to isolate lifecycle retention from overlay hit tests.
        compose.activityRule.scenario.onActivity { navigation.controller.manuallyExploring() }
        onView(isAssignableFrom(MapView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): org.hamcrest.Matcher<android.view.View> = isAssignableFrom(MapView::class.java)
            override fun getDescription() = "Set a synthetic explored native camera"
            override fun perform(ui: UiController, view: android.view.View) {
                (view as MapView).getMapAsync { it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(52.523, 13.405), 15.0)) }
                ui.loopMainThreadUntilIdle()
            }
        })
        compose.waitUntil(10_000) {
            var moved = false
            compose.activityRule.scenario.onActivity { moved = GeoMath.distance(navigation.camera.center, target) > 20 && navigation.camera.appliedRequestId > 0 }
            moved
        }
        lateinit var exploredCamera: MapCamera
        exploredCamera = settledCamera(navigation)
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Loading map…").fetchSemanticsNodes().isEmpty() }
            val restoredCamera = settledCamera(navigation)
            compose.onNodeWithTag("navigation_screen").assertIsDisplayed()
            val screen = compose.onNodeWithTag("navigation_screen").fetchSemanticsNode().size
            val panel = compose.onNodeWithTag("navigation_panel").fetchSemanticsNode().size
            assertTrue(panel.width < screen.width * 0.6)
            compose.activityRule.scenario.onActivity {
                assertTrue(vehicle === ViewModelProvider(it)[VehicleViewModel::class.java])
                assertTrue(music === ViewModelProvider(it)[MusicViewModel::class.java])
                assertTrue(navigation === ViewModelProvider(it)[NavigationViewModel::class.java])
                assertEquals(10, vehicle.vehicleStatus.value.speedKmh)
                assertEquals(target, navigation.state.value.destination?.point)
                assertCameraRetained(exploredCamera, restoredCamera, "Rotation")
            }
            compose.onNodeWithText("Dashboard").performClick()
            compose.onNodeWithText("Vehicle overview").assertIsDisplayed()
            compose.onNodeWithText("Navigate").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Loading map…").fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            val returnedCamera = settledCamera(navigation)
            compose.activityRule.scenario.onActivity {
                assertCameraRetained(restoredCamera, returnedCamera, "Tab return")
            }
            compose.onNodeWithText("Dashboard").performClick()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
            // Finish restoring the original orientation before ActivityScenario teardown.
            compose.waitUntil(20_000) {
                compose.activityRule.scenario.state == Lifecycle.State.RESUMED &&
                    compose.activity.resources.configuration.orientation == originalConfigurationOrientation
            }
            compose.waitForIdle()
        }
    }
    private fun assertCameraRetained(expected: MapCamera, actual: MapCamera, action: String) {
        // A resized native viewport can adjust its center by a few map pixels. Verify
        // visual retention at the saved zoom; replaying Overview moves hundreds of pixels.
        val metersPerMapPixel = 40_075_016.686 * kotlin.math.cos(Math.toRadians(expected.center.latitude)) /
            (512 * Math.pow(2.0, expected.zoom))
        val driftPixels = GeoMath.distance(expected.center, actual.center) / metersPerMapPixel
        assertTrue("$action moved $driftPixels map pixels", driftPixels < 12)
        assertEquals(expected.zoom, actual.zoom, 0.02)
        assertEquals(expected.appliedRequestId, actual.appliedRequestId)
    }
    private fun settledCamera(navigation: NavigationViewModel): MapCamera {
        var camera = navigation.camera
        var unchangedSince = android.os.SystemClock.elapsedRealtime()
        // Native gesture inertia runs outside Compose's animation clock.
        compose.waitUntil(10_000) {
            lateinit var latest: MapCamera
            compose.activityRule.scenario.onActivity { latest = navigation.camera }
            if (GeoMath.distance(camera.center, latest.center) > 0.1 || kotlin.math.abs(camera.zoom - latest.zoom) > 0.01) {
                camera = latest; unchangedSince = android.os.SystemClock.elapsedRealtime()
            }
            android.os.SystemClock.elapsedRealtime() - unchangedSince >= 1_000
        }
        return camera
    }
}
