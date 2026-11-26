package com.example.autohmi

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.ui.History
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Read-only synthetic history; never opens the production database. */
class CompletedNavigationHistoryTest {
    @get:Rule val compose = createDisplayContentRule()
    private val at = Instant.parse("2026-11-26T10:00:00Z").toEpochMilli()
    private val newest = CompletedNavigationTrip("newest", at, 11.4, 95.0, 30.0,
        destinationLabel = "Selected park", destinationGapMeters = 80.0)
    private val older = CompletedNavigationTrip("older", at - 60_000, 18.0, 150.0, 30.0,
        destinationLabel = "City road")

    private fun show(largeText: Boolean = false, onDelete: (Long) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.3f else 1f)) {
                AutoHMITheme { Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                        Box(Modifier.width(1100.dp).height(560.dp).testTag("history_safe_area")) {
                            History(HistoryState(snapshots = listOf(TripSnapshot(42, at - 120_000, 4.5, 25.0, "ECO")),
                                isLoading = false, completedNavigationTrips = listOf(newest, older)), expanded = true, onDelete = onDelete)
                        }
                    }
                } }
            }
        }
    }
    @Test fun completedNavigationIsSeparateNewestFirstAndSnapshotDeletionStillWorks() {
        var deleted: Long? = null
        show(onDelete = { deleted = it })
        compose.onNodeWithText("Completed navigation trips").assertIsDisplayed()
        val first = compose.onNodeWithTag("completed_trip_newest").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("completed_trip_older").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < second.top || (first.top == second.top && first.left < second.left))
        compose.onAllNodesWithText("DEMO · COMPLETED").assertCountEquals(2)
        compose.onAllNodesWithText("Driving endpoint reached").assertCountEquals(2)
        compose.onNodeWithText("95 m driven").assertIsDisplayed()
        compose.onNodeWithText("11.4 s simulated driving time").assertIsDisplayed()
        compose.onNodeWithText("Selected destination approximately 80 m", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Vehicle snapshots"))
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Snapshot #42"))
        compose.onNodeWithText("Snapshot #42").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(42L, deleted) }
    }
    @Test fun automotiveHistoryAtFontScale13ShowsRealStatisticsAndEndpointGap() {
        show(largeText = true)
        compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("completed_trip_newest"))
        compose.onNodeWithText("Selected park").assertIsDisplayed()
        compose.onNodeWithText("11.4 s simulated driving time").assertIsDisplayed()
        compose.onNodeWithText("Selected destination approximately 80 m", substring = true).assertIsDisplayed()
        val safe = compose.onNodeWithTag("history_safe_area").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("completed_trip_newest").fetchSemanticsNode().boundsInRoot
        assertTrue(card.left >= safe.left && card.right <= safe.right)
        compose.onNodeWithText("Selected park").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>(); assertTrue(action(layouts)); assertFalse(layouts.single().hasVisualOverflow)
        }
        if (InstrumentationRegistry.getArguments().getString("historyScreenshot") == "true") {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(instrumentation.targetContext.cacheDir, "completed-navigation-history-font13.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
