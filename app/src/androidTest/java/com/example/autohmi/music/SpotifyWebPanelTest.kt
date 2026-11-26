package com.example.autohmi.music

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import com.example.autohmi.createDisplayContentRule as createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import com.example.autohmi.music.ui.SpotifyWebPanel
import com.example.autohmi.music.web.ConnectDevice
import com.example.autohmi.music.web.SpotifyWebState
import com.example.autohmi.music.web.WebAuthStatus
import com.example.autohmi.music.web.WebIssue
import com.example.autohmi.ui.theme.AutoHMITheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SpotifyWebPanelTest {
    @get:Rule val compose = createComposeRule()
    private val device = ConnectDevice("fixture-windows", "Windows fixture", "Computer", true, false)

    @Test fun browserAuthorizationIsAnExplicitUiActionAndLocalFallbackRemainsAvailable() {
        var authorizations = 0; var local = 0
        show(SpotifyWebState(auth = WebAuthStatus.SIGNED_OUT), onAuthorize = { authorizations++ }, onLocal = { local++ })
        compose.onNodeWithText("Authorize in browser").performClick()
        compose.onNodeWithText("Local files").performClick()
        compose.runOnIdle { assertEquals(1, authorizations); assertEquals(1, local) }
    }
    @Test fun authorizedWithoutDevicesShowsWindowsInstructionsRatherThanAReadyPlayer() {
        show(SpotifyWebState(auth = WebAuthStatus.AUTHORIZED, issue = WebIssue.NO_DEVICE))
        compose.onNodeWithText("Spotify authorized").assertIsDisplayed()
        compose.onNodeWithTag("spotify_web_list").performScrollToNode(hasText("No Spotify Connect devices found.", substring = true))
        compose.onNodeWithText("No Spotify Connect devices found.", substring = true).assertIsDisplayed()
        revealPlayer("Play")
        compose.onNodeWithText("Play").assertIsNotEnabled()
    }
    @Test fun observableDeviceAndPlayerStateRenderArtworkAndDoNotFakePlaySuccess() {
        val web = mutableStateOf(SpotifyWebState(auth = WebAuthStatus.AUTHORIZED, devices = listOf(device), selectedDeviceId = device.id, playbackDevice = device))
        val png = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        val player = mutableStateOf(MusicPlayerState(track = MusicTrack("spotify:track:fixture", "Fixture title", "Fixture artist", 60000),
            positionMs = 1000, canPlay = true, canNext = true, artworkBytes = png))
        var plays = 0; var selections = 0
        compose.setContent { AutoHMITheme {
            SpotifyWebPanel(player.value, web.value, {}, {}, {}, {}, {}, { selections++ }, { plays++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("spotify_web_list").performScrollToNode(hasText("Selected: Windows fixture", substring = true))
        compose.onNodeWithText("Selected: Windows fixture", substring = true).performClick()
        revealPlayer("Play")
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithText("0:01").assertIsDisplayed()
        compose.onNodeWithContentDescription("Album artwork").assertExists()
        compose.runOnIdle { assertEquals(1, plays); assertEquals(1, selections) }
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.runOnIdle { player.value = player.value.copy(isPlaying = true, canPlay = false, canPause = true) }
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.runOnIdle { web.value = web.value.copy(stale = true) }
        compose.onNodeWithText("Pause").assertIsNotEnabled()
        revealPlayer("Fixture title")
        compose.onNodeWithText("Fixture title").assertIsDisplayed()
    }
    @Test fun expiredSessionOffersReauthorizationWithoutAnyCredentialFields() {
        show(SpotifyWebState(auth = WebAuthStatus.SIGNED_OUT, issue = WebIssue.SESSION_EXPIRED))
        compose.onNodeWithText("Your Spotify session expired", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Authorize in browser").assertIsDisplayed()
    }
    private fun revealPlayer(text: String) {
        if (compose.onAllNodesWithTag("music_two_pane").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("spotify_web_list").performScrollToNode(hasText(text))
        }
    }
    private fun show(web: SpotifyWebState, onAuthorize: () -> Unit = {}, onLocal: () -> Unit = {}) {
        compose.setContent { AutoHMITheme { SpotifyWebPanel(MusicPlayerState(), web, onLocal, onAuthorize, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
    }
}
