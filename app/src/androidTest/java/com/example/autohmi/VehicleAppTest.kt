package com.example.autohmi

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import com.example.autohmi.createDisplayContentRule as createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.autohmi.ui.VehicleApp
import com.example.autohmi.ui.theme.AutoHMITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VehicleAppTest {
    @get:Rule val compose = createComposeRule()

    private fun showApp(vehicle: VehicleStatus = VehicleStatus(), onReset: () -> Unit = {}, onDelete: (Long) -> Unit = {}) {
        compose.setContent {
            AutoHMITheme {
                VehicleApp(
                    vehicle = vehicle,
                    history = HistoryState(
                        snapshots = listOf(TripSnapshot(42, 1_000, 2.5, 38.0, "ECO")),
                        isLoading = false
                    ),
                    settingsReady = true,
                    settingsError = false,
                    isSaving = false,
                    snackbar = SnackbarHostState(),
                    onAccelerate = {}, onBrake = {}, onToggleSimulation = {},
                    onDrivingModeChange = {}, onResetTrip = onReset, onSaveSnapshot = {},
                    onDeleteSnapshot = onDelete
                )
            }
        }
    }

    @Test
    fun emptyTripCannotBeSavedAndEmptyBatteryCannotStartOrAccelerate() {
        showApp(VehicleStatus(batteryEnergyKwh = 0.0))
        compose.onNodeWithText("Start simulation").assertIsNotEnabled()
        compose.onNodeWithText("Accelerate +10").assertIsNotEnabled()
        compose.onNodeWithText("Battery depleted").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_list").performScrollToNode(hasText("Save snapshot"))
        compose.onNodeWithText("Save snapshot").assertIsNotEnabled()
    }

    @Test
    fun deletionRequiresConfirmationAndCanBeCancelled() {
        var deleted: Long? = null
        showApp(onDelete = { deleted = it })
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Snapshot #42").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(null, deleted)
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(42L, deleted) }
    }

    @Test
    fun resetRequiresConfirmationAndSettingsExplainTimeScale() {
        var resets = 0
        showApp(VehicleStatus(distanceKm = 1.0), onReset = { resets++ })
        compose.onNodeWithTag("dashboard_list").performScrollToNode(hasText("Reset trip"))
        compose.onNodeWithText("Reset trip").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, resets)
        compose.onNodeWithText("Reset trip").performClick()
        compose.onNode(hasText("Reset trip") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, resets) }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("About the simulation"))
        compose.onNodeWithText("About the simulation").assertIsDisplayed()
    }
}
