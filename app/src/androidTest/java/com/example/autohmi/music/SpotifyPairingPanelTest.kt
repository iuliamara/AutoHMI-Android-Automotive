package com.example.autohmi.music

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.example.autohmi.createDisplayContentRule
import com.example.autohmi.music.pairing.*
import com.example.autohmi.music.ui.SpotifyPairingPanel
import com.example.autohmi.ui.theme.AutoHMITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SpotifyPairingPanelTest {
    @get:Rule val compose = createDisplayContentRule()
    @Test fun waitingQrOffersCancelRegenerateAndUpdatesToExpired() {
        val state = mutableStateOf(SpotifyPairingState(PairingPhase.WAITING, "https://relay.test.invalid/pair/" + "Q".repeat(43), 250))
        var retries = 0; var cancels = 0
        compose.setContent { AutoHMITheme { SpotifyPairingPanel(state.value, { retries++ }, { cancels++ }) } }
        compose.onNodeWithText("Scan with your phone").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pairing_qr").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Spotify pairing QR code").assertIsDisplayed()
        compose.onNodeWithTag("pairing_countdown").assertTextContains("4:10", substring = true)
        compose.onNodeWithText("Generate new QR").performClick(); compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, cancels); state.value = SpotifyPairingState(PairingPhase.EXPIRED) }
        compose.onNodeWithText("QR expired").assertIsDisplayed(); compose.onNodeWithTag("pairing_qr").assertDoesNotExist()
    }
    @Test fun connectingAndSuccessNeverRetainTheQr() {
        val state = mutableStateOf(SpotifyPairingState(PairingPhase.CONNECTING))
        compose.setContent { AutoHMITheme { SpotifyPairingPanel(state.value, {}, {}) } }
        compose.onNodeWithText("Connecting to Spotify…").assertIsDisplayed(); compose.onNodeWithTag("pairing_qr").assertDoesNotExist()
        compose.runOnIdle { state.value = SpotifyPairingState(PairingPhase.CONNECTED) }
        compose.onNodeWithText("Spotify connected").assertIsDisplayed()
        compose.onNodeWithText("Continue to devices").assertIsDisplayed()
    }
    @Test fun unconfiguredScreenDoesNotFabricateAQr() {
        compose.setContent { AutoHMITheme { SpotifyPairingPanel(SpotifyPairingState(PairingPhase.UNCONFIGURED), {}, {}) } }
        compose.onNodeWithText("QR setup required").assertIsDisplayed(); compose.onNodeWithTag("pairing_qr").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
