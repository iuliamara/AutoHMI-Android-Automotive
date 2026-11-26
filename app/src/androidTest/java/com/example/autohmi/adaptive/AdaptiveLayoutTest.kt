package com.example.autohmi.adaptive

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import com.example.autohmi.createDisplayComposeRule as createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.*
import com.example.autohmi.R
import com.example.autohmi.music.*
import com.example.autohmi.music.ui.MusicPanel
import com.example.autohmi.music.ui.SpotifyWebPanel
import com.example.autohmi.music.web.*
import com.example.autohmi.navigation.*
import com.example.autohmi.navigation.ui.*
import com.example.autohmi.ui.VehicleApp
import com.example.autohmi.ui.usesExpandedLayout
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Only synthetic UI models. No real repositories, account, browser, GPS or file library. */
class AdaptiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = mutableStateOf(MusicSource.LOCAL)
    private val webIssue = mutableStateOf<WebIssue?>(null)
    private val auth = mutableStateOf(WebAuthStatus.AUTHORIZED)
    private val fontScale = mutableFloatStateOf(1f)
    private val narrow = mutableStateOf(false)
    private val player = MusicPlayerState(MusicTrack("demo", "Night drive demo", "AutoHMI sample", 240_000),
        positionMs = 75_000, canPlay = true, canPause = true, canNext = true, canPrevious = true, canSeek = true)
    private val mapStatus = AtomicReference(MapLoadStatus.LOADING)
    private val args get() = InstrumentationRegistry.getArguments()

    private fun show() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)) }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                AutoHMITheme {
                    val vehicle = remember { mutableStateOf(VehicleStatus(speedKmh = 60, distanceKm = 12.5)) }
                    val tracks = remember { listOf(player.track!!, MusicTrack("demo2", "City lights demo", "AutoHMI sample", 185_000)) }
                    val music = MusicUiState(source = source.value, player = player, tracks = tracks)
                    val device = ConnectDevice("demo-windows", "Demo Windows player", "Computer", true, false)
                    val web = SpotifyWebState(auth = auth.value, issue = webIssue.value, devices = listOf(device),
                        selectedDeviceId = device.id, playbackDevice = device)
                    val history = HistoryState((1L..4L).map { TripSnapshot(it, Instant.parse("2026-11-22T10:00:00Z").toEpochMilli(), it * 12.5, 39.0 - it, "NORMAL") }, isLoading = false)
                    Box(Modifier.fillMaxSize()) {
                    Box(if (narrow.value) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        VehicleApp(vehicle.value, history, true, false, false, remember { SnackbarHostState() },
                            {}, {}, {}, { vehicle.value = vehicle.value.copy(drivingMode = it) }, {}, {}, {},
                            musicState = music, musicContent = {
                                if (source.value == MusicSource.LOCAL) MusicPanel(music, { source.value = it }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                                else SpotifyWebPanel(player, web, { source.value = MusicSource.LOCAL }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                            }, navigationContent = {
                                var state by remember { mutableStateOf(NavigationState(
                                    location = LocationState(LocationPermission.PRECISE, LocationStatus.AVAILABLE, LocationFix(GeoPoint(52.52, 13.405), 12.0, 1000, true)),
                                    destination = Destination(GeoPoint(52.522, 13.409), "Demo destination"), phase = TripPhase.DESTINATION_SELECTED, followLocation = false)) }
                                NavigationLayout(state, {}, {}, {}, { point, name -> state = state.copy(destination = Destination(point, name ?: "Demo destination")) }, {}, {}, {}, {}, {}, {}, {},
                                    { state = state.copy(theme = if (state.theme == MapTheme.DARK) MapTheme.LIGHT else MapTheme.DARK) }, {},
                                    map = { modifier, retry, callback -> NavigationMap(state, MapCamera(GeoPoint(52.52, 13.405), 14.5),
                                        if (args.getString("liveMaps") == "true") MapMode.LIVE else MapMode.OFFLINE_TEST, retry, { _, _ -> }, {}, {},
                                        { mapStatus.set(it); callback(it) }, modifier) })
                            })
                    }
                    }
                }
            }
        }
    }

    @Test fun primaryReadoutsAndActionsStayInsideSafeDrawingBounds() {
        show()
        assertDashboardFits()
    }
    @Test fun increasedFontScaleKeepsPrimaryInformationAndNavigationAccessible() {
        fontScale.floatValue = 1.3f
        show()
        assertDashboardFits()
        tab("Settings")
        DrivingMode.entries.forEach { assertSingleLineText(it.name) }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("About the simulation"))
        compose.onNodeWithText("About the simulation").assertIsDisplayed()
    }
    @Test fun sectionSelectionSurvivesChangesInAvailableWidth() {
        show(); tab("Settings")
        compose.runOnIdle { narrow.value = true }
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNode(hasText("Settings") and isSelected()).assertIsDisplayed()
        compose.runOnIdle { narrow.value = false }
        compose.onNode(hasText("Settings") and isSelected()).assertIsDisplayed()
    }
    @Test fun historyCountFollowsItsHeadingAtIncreasedFontScale() {
        fontScale.floatValue = 1.3f
        show(); tab("History")
        assertHistoryHeadingFits()
    }
    @Test fun expandedMusicKeepsPlaybackOutsideScrollingSecondaryLists() {
        fontScale.floatValue = 1.3f
        show(); tab("Music")
        val expanded = compose.onAllNodesWithTag("music_two_pane").fetchSemanticsNodes().isNotEmpty()
        if (expanded) {
            val top = compose.onNodeWithTag("playback_controls").getUnclippedBoundsInRoot().top
            visible("Play"); visible("Next"); visible("Previous")
            compose.onNodeWithTag("music_list").performScrollToNode(hasText("City lights demo"))
            assertEquals(top, compose.onNodeWithTag("playback_controls").getUnclippedBoundsInRoot().top)
        } else {
            compose.onNodeWithTag("music_list").performScrollToNode(hasText("Previous"))
        }
        assertSingleLineText("Previous")
        compose.runOnIdle { source.value = MusicSource.SPOTIFY }
        if (expanded) { visible("Play"); visible("Next"); visible("Previous") }
        compose.runOnIdle { webIssue.value = WebIssue.ACCESS_DENIED }
        compose.onNodeWithTag("spotify_web_list").performScrollToNode(hasText(webIssueMessageForTest()))
        compose.onNodeWithText(webIssueMessageForTest()).assertIsDisplayed()
        compose.runOnIdle { auth.value = WebAuthStatus.SIGNED_OUT }
        compose.onNodeWithTag("spotify_web_list").performScrollToNode(hasText("Authorize in browser"))
        compose.onNodeWithText("Authorize in browser").assertIsDisplayed()
    }
    private fun webIssueMessageForTest() = com.example.autohmi.music.ui.webIssueMessage(WebIssue.ACCESS_DENIED)
    private fun assertSingleLineText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$text stays on one line", 1, layouts.single().lineCount)
        assertTrue("$text fits without ellipsis", !layouts.single().isLineEllipsized(0))
    }
    private fun assertHistoryHeadingFits() {
        val description = compose.onNodeWithText(compose.activity.getString(R.string.history_description))
        val count = compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.history_count, 4, 4))
        description.assertIsDisplayed()
        count.assertIsDisplayed()
        assertTrue("Snapshot count is below the heading", count.getUnclippedBoundsInRoot().top >= description.getUnclippedBoundsInRoot().bottom)
    }
    private fun assertDashboardFits() {
        var expectedExpanded = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val safe = WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = activity.resources.displayMetrics.density
            expectedExpanded = usesExpandedLayout((view.width - safe.left - safe.right) / density,
                (view.height - safe.top - safe.bottom) / density)
        }
        val expanded = compose.onAllNodesWithTag("navigation_rail").fetchSemanticsNodes().isNotEmpty()
        assertEquals("Navigation matches the measured safe window", expectedExpanded, expanded)
        compose.onNodeWithTag(if (expanded) "navigation_rail" else "bottom_navigation").assertIsDisplayed()
        if (expanded) {
            listOf("vehicle_speed", "vehicle_battery", "vehicle_range").forEach { visibleTag(it) }
            visible("Start simulation"); visible("Accelerate +10"); visible(compose.activity.getString(R.string.brake))
            listOf("Dashboard","Music","Navigate","History","Settings").forEach { visible(it) }
        } else {
            listOf("Dashboard","Music","Navigate","History","Settings").forEach { assertSingleLineText(it) }
        }
    }
    private fun visible(text: String) = fullyVisible(compose.onNode(hasText(text) and hasClickAction()))
    private fun visibleTag(tag: String) = fullyVisible(compose.onNodeWithTag(tag))
    private fun fullyVisible(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        var insetTop = 0f; var insetBottom = 0f; var insetLeft = 0f; var insetRight = 0f
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val safe = WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = activity.resources.displayMetrics.density
            insetTop = safe.top / density; insetBottom = safe.bottom / density; insetLeft = safe.left / density; insetRight = safe.right / density
        }
        assertTrue("Control left inside safe area", bounds.left.value >= root.left.value + insetLeft - 1)
        assertTrue("Control right inside safe area", bounds.right.value <= root.right.value - insetRight + 1)
        assertTrue("Control top inside safe area", bounds.top.value >= root.top.value + insetTop - 1)
        assertTrue("Control bottom inside safe area", bounds.bottom.value <= root.bottom.value - insetBottom + 1)
    }

    @Test fun captureSyntheticScreens() {
        val stage = args.getString("layoutCapture").orEmpty()
        assumeTrue("Opt-in synthetic screenshot suite", stage in setOf("before", "after"))
        fontScale.floatValue = args.getString("fontScale")?.toFloat() ?: 1f
        show()
        tab("Music"); capture(stage, "music-local")
        compose.runOnIdle { source.value = MusicSource.SPOTIFY }; capture(stage, "music-spotify")
        compose.runOnIdle { webIssue.value = WebIssue.ACCESS_DENIED }; capture(stage, "music-error")
        compose.runOnIdle { auth.value = WebAuthStatus.SIGNED_OUT; webIssue.value = WebIssue.CONFIGURATION }; capture(stage, "music-authorization")
        tab("History"); assertHistoryHeadingFits(); capture(stage, "history")
        tab("Settings"); DrivingMode.entries.forEach { assertSingleLineText(it.name) }; capture(stage, "settings")
        tab("Navigate")
        compose.waitUntil(60_000) { mapStatus.get() == MapLoadStatus.READY }
        capture(stage, "navigation-dark")
        compose.onNodeWithText("Day map").performClick()
        compose.waitUntil(60_000) { mapStatus.get() == MapLoadStatus.READY }
        capture(stage, "navigation-light")
        // Release the native renderer while its host window is still resumed.
        compose.runOnIdle { source.value = MusicSource.LOCAL }
        tab("Dashboard")
        compose.waitForIdle()
        // Car system bars can settle after initial focus. Capture the final safe area.
        assertDashboardFits()
        capture(stage, "dashboard")
        recordWindow(stage)
    }
    private fun tab(name: String) = compose.onNode(hasText(name) and hasClickAction()).performClick()
    private fun capture(stage: String, name: String) {
        compose.waitForIdle()
        if (name.startsWith("navigation")) compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading map…").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(compose.activity.window.decorView.width, compose.activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        instrumentation.runOnMainSync {
            PixelCopy.request(compose.activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy completed", copied.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        FileOutputStream(File(instrumentation.targetContext.cacheDir, "layout-$stage-$name.png")).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun recordWindow(stage: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val insets = WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val config = activity.resources.configuration
            val result = JSONObject().put("displayId", view.display.displayId).put("widthPx", view.width).put("heightPx", view.height)
                .put("widthDp", config.screenWidthDp).put("heightDp", config.screenHeightDp).put("density", activity.resources.displayMetrics.density)
                .put("safeLeft", insets.left).put("safeTop", insets.top).put("safeRight", insets.right).put("safeBottom", insets.bottom)
                .put("fontScale", fontScale.floatValue).put("capturedAtEpochMs", System.currentTimeMillis())
                .put("liveMaps", args.getString("liveMaps") == "true")
            File(activity.cacheDir, "layout-$stage-window.json").writeText(result.toString(2))
        }
    }
}
