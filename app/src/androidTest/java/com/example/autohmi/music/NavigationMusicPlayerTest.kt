package com.example.autohmi.music

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.HistoryState
import com.example.autohmi.VehicleStatus
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.music.web.*
import com.example.autohmi.navigation.*
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.VehicleApp
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapView

/** UI-only fixture: no controllers, account/session access, actual playback, GPS or network. */
class NavigationMusicPlayerTest {
    @get:Rule val compose = createDisplayContentRule()
    private val origin = GeoPoint(52.52, 13.405)
    private val end = GeoPoint(52.521, 13.405)
    private val navigation = NavigationState(destination = Destination(end),
        route = RoutePlan(origin, Destination(end), listOf(origin, end), 120.0, 60.0),
        phase = TripPhase.TRIP_ACTIVE, routingConfigured = true, followLocation = false,
        demoDrive = DemoDriveState(origin, 40.0, 120.0), remainingMeters = 80.0, remainingSeconds = 9.6)
    private fun track() = MusicUiState(player = MusicPlayerState(
        track = MusicTrack("test-only", "Synthetic night drive", "Portfolio fixture artist"),
        isPlaying = true, canPlay = true, canPause = true, canNext = true))

    @Composable private fun App(music: MusicUiState, web: SpotifyWebState = SpotifyWebState(), native: Boolean = false,
                                ready: (MapLoadStatus) -> Unit = {}, toggle: () -> Unit = {}, next: () -> Unit = {}) {
        VehicleApp(VehicleStatus(), HistoryState(), true, false, false, remember { SnackbarHostState() },
            {}, {}, {}, {}, {}, {}, {}, musicState = music, onMusicToggle = toggle, onMusicNext = next,
            spotifyWebState = web, navigationContent = {
                NavigationLayout(navigation, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    map = { modifier, retry, status ->
                        if (native) NavigationMap(navigation, MapCamera(origin, 16.0), MapMode.OFFLINE_TEST,
                            retry, { _, _ -> }, {}, {}, { ready(it); status(it) }, modifier)
                        else Box(modifier) { SideEffect { status(MapLoadStatus.READY) } }
                    })
            })
    }

    private fun assertCollapsed(collapsed: Boolean) {
        compose.onNodeWithTag("navigation_music_player").assertIsDisplayed().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, if (collapsed) "Collapsed" else "Expanded"))
        compose.waitForIdle()
    }

    @Test fun idleNavigationHidesPlayerAndTrackUsesExistingArtworkAndActions() {
        val music = mutableStateOf(MusicUiState())
        var toggles = 0
        var next = 0
        compose.setContent { AutoHMITheme { App(music.value, toggle = { toggles++ }, next = { next++ }) } }
        compose.onNodeWithText("Navigate").performClick()
        compose.onNodeWithTag("navigation_music_player").assertDoesNotExist()
        val emptyHeight = compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.height
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        compose.runOnIdle { music.value = track().copy(player = track().player.copy(artworkBytes = bytes)) }
        assertCollapsed(false)
        compose.onNodeWithTag("navigation_music_title", useUnmergedTree = true).assertTextEquals("Synthetic night drive")
        compose.onNodeWithTag("navigation_music_artist", useUnmergedTree = true).assertTextEquals("Portfolio fixture artist")
        compose.onNodeWithTag("navigation_music_source").assertTextEquals("Local")
        val artDescription = compose.activity.getString(com.example.autohmi.R.string.album_artwork)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(artDescription).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(artDescription).assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.height < emptyHeight)
        compose.onNodeWithTag("navigation_music_toggle").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("navigation_music_next").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, toggles)
        assertEquals(1, next)
        compose.runOnIdle { music.value = music.value.copy(player = music.value.player.copy(isPlaying = false, positionMs = 1000)) }
        assertCollapsed(false)
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse music player").performClick()
        assertCollapsed(true)
        compose.runOnIdle { music.value = MusicUiState(player = MusicPlayerState(isPlaying = true, canPause = true)) }
        assertCollapsed(true) // Missing metadata does not hide an observed active session.
        compose.runOnIdle { music.value = MusicUiState() }
        compose.onNodeWithTag("navigation_music_player").assertDoesNotExist()
        assertEquals(emptyHeight, compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.height, 1f)
        // Other screens retain their existing empty-state player.
        compose.onNodeWithText("Dashboard").performClick()
        compose.onNodeWithText("Nothing playing yet").assertIsDisplayed()
    }

    @Test fun localSwipesChevronAndRestorationKeepPlaybackAndObservedSpotifyDevice() {
        val music = mutableStateOf(track().copy(source = MusicSource.SPOTIFY))
        val web = mutableStateOf(SpotifyWebState(auth = WebAuthStatus.AUTHORIZED, selectedDeviceId = "different-selected-id",
            playbackDevice = ConnectDevice("fixture-pc", "Windows demo PC", "Computer", true, false)))
        var toggles = 0
        var next = 0
        val restore = StateRestorationTester(compose)
        restore.setContent { AutoHMITheme { App(music.value, web.value, toggle = { toggles++ }, next = { next++ }) } }
        compose.onNodeWithText("Navigate").performClick()
        compose.onNodeWithTag("navigation_music_source").assertTextEquals("Spotify · Remote")
        compose.onNodeWithTag("navigation_music_device").assertTextEquals("Windows demo PC")
        compose.onNodeWithTag("navigation_music_player").performTouchInput {
            swipe(Offset(width * .5f, 8f), Offset(width * .5f, height - 8f), 450)
        }
        assertCollapsed(true)
        compose.onNodeWithTag("navigation_music_strip_label").assertTextEquals("Spotify · Remote: Windows demo PC")
        compose.runOnIdle { music.value = music.value.copy(player = music.value.player.copy(positionMs = 2000)) }
        restore.emulateSavedInstanceStateRestore()
        assertCollapsed(true)
        compose.onNodeWithTag("navigation_music_player").performTouchInput {
            swipe(Offset(width * .5f, height - 3f), Offset(width * .5f, 3f), 450)
        }
        assertCollapsed(false)
        // A vertical swipe on a playback button cancels its tap instead of sending Pause.
        compose.onNodeWithTag("navigation_music_toggle").performTouchInput {
            swipe(Offset(center.x, 2f), Offset(center.x, height - 2f), 450)
        }
        assertCollapsed(true)
        compose.onNodeWithContentDescription("Expand music player").performClick()
        assertCollapsed(false)
        compose.onNodeWithTag("navigation_music_player").performTouchInput { swipeLeft() }
        assertCollapsed(false)
        compose.onNodeWithContentDescription("Hide navigation information").assertIsDisplayed()
        assertEquals(0, toggles)
        assertEquals(0, next)
        assertTrue(music.value.player.isPlaying)
        compose.runOnIdle { web.value = web.value.copy(stale = true) }
        compose.onNodeWithTag("navigation_music_source").assertTextEquals("Spotify · Device status unavailable")
        compose.onNodeWithTag("navigation_music_toggle").assertIsNotEnabled()
        compose.onNodeWithTag("navigation_music_next").assertIsNotEnabled()
    }

    @Test fun mapGrowsWithoutRecreationAndBarFitsAtNormalAndLargeFonts() {
        val font = mutableStateOf(1f)
        val music = mutableStateOf(track())
        val loaded = AtomicReference(MapLoadStatus.LOADING)
        var commands = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font.value)) { AutoHMITheme {
                App(music.value, native = true, ready = loaded::set, toggle = { commands++ }, next = { commands++ })
            } }
        }
        compose.onNodeWithText("Navigate").performClick()
        compose.waitUntil(20_000) { loaded.get() == MapLoadStatus.READY }
        compose.onNodeWithContentDescription("Hide navigation information").performClick()
        var mapView: MapView? = null
        fun camera(): CameraPosition {
            val value = AtomicReference<CameraPosition>()
            onView(isAssignableFrom(MapView::class.java)).perform(object : ViewAction {
                override fun getConstraints() = isAssignableFrom(MapView::class.java)
                override fun getDescription() = "Check mounted map identity and camera during music resizing"
                override fun perform(ui: UiController, view: View) {
                    if (mapView == null) mapView = view as MapView else assertSame(mapView, view)
                    (view as MapView).getMapAsync { value.set(it.cameraPosition) }
                    ui.loopMainThreadUntilIdle()
                }
            })
            return requireNotNull(value.get())
        }
        for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { font.value = scale }
            assertCollapsed(false)
            val before = camera()
            val height = compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.height
            for (collapsed in listOf(false, true)) {
                if (collapsed) compose.onNodeWithContentDescription("Collapse music player").performClick()
                assertCollapsed(collapsed)
                val bar = compose.onNodeWithTag("navigation_music_player").fetchSemanticsNode().boundsInRoot
                val nav = compose.onNodeWithTag("navigation_content_safe_area").fetchSemanticsNode().boundsInRoot
                assertTrue(nav.bottom <= bar.top + 1f)
                for (tag in listOf("demo_speedometer", "collapsed_navigation_info", "navigation_sidebar_handle", "navigation_map_controls")) {
                    assertFalse("Music overlaps $tag at $scale", bar.overlaps(compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot))
                }
                for (label in listOf("Zoom in", "Zoom out")) assertFalse(bar.overlaps(
                    compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot))
                for (tag in listOf("navigation_music_chevron", "navigation_music_toggle", "navigation_music_next")) {
                    val control = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                    assertTrue(bar.contains(control.topLeft) && bar.contains(control.bottomRight - Offset(.1f, .1f)))
                }
                val after = camera()
                assertEquals(before.zoom, after.zoom, .02)
                assertEquals(before.target!!.latitude, after.target!!.latitude, .0001)
                assertEquals(before.target!!.longitude, after.target!!.longitude, .0001)
                if (collapsed) assertTrue(compose.onNodeWithTag("navigation_map").fetchSemanticsNode().boundsInRoot.height > height + 10)
                assertTrue(music.value.player.isPlaying)
                screenshot("navigation-music-${if (collapsed) "strip" else "compact"}-$scale.png")
            }
            compose.onNodeWithContentDescription("Expand music player").performClick()
        }
        assertEquals(0, commands)
        compose.runOnIdle { music.value = MusicUiState() }
        compose.onNodeWithTag("navigation_music_player").assertDoesNotExist()
        camera() // Empty-state hiding also retains the exact native MapView.
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("navigationMusicScreenshot") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
