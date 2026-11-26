package com.example.autohmi.music

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import com.example.autohmi.createDisplayContentRule as createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import com.example.autohmi.music.ui.MusicPanel
import com.example.autohmi.ui.theme.AutoHMITheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MusicPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localFallbackShowsPickerAndEmptyLibraryWithoutSpotify() {
        var imports = 0
        show({ MusicUiState() }, onAdd = { imports++ })
        compose.onNodeWithTag("music_list").performScrollToNode(hasText("Add audio files"))
        compose.onNodeWithText("Add audio files").performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithTag("music_list").performScrollToNode(hasText("No audio files selected.", substring = true))
        compose.onNodeWithText("No audio files selected.", substring = true).assertIsDisplayed()
    }

    @Test
    fun missingSpotifyShowsActionableErrorAndDisablesPlayback() {
        var connects = 0
        show({ MusicUiState(source = MusicSource.SPOTIFY, spotify = SpotifyConnectionState(SpotifyConnectionStatus.ERROR, MusicIssue.NOT_INSTALLED)) }, onConnect = { connects++ })
        compose.onNodeWithText("Spotify is not installed.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Connect Spotify").performClick()
        compose.runOnIdle { assertEquals(1, connects) }
        revealPlayback()
        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").assertIsNotEnabled()
    }

    @Test
    fun observablePlayerEventsUpdateTrackAndControlsWithoutOptimisticPlayback() {
        val state = mutableStateOf(MusicUiState(
            source = MusicSource.SPOTIFY,
            spotify = SpotifyConnectionState(SpotifyConnectionStatus.CONNECTED),
            player = MusicPlayerState(track = MusicTrack("test", "Example track", "Example artist", 60_000), canPlay = true)
        ))
        var plays = 0
        show({ state.value }, onPlay = { plays++ })
        revealPlayback()
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertEquals(1, plays) }
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(player = state.value.player.copy(isPlaying = true, canPlay = false, canPause = true)) }
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    private fun revealPlayback() {
        if (compose.onAllNodesWithTag("music_two_pane").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("music_list").performScrollToNode(hasText("Play"))
        }
    }
    private fun show(state: () -> MusicUiState, onConnect: () -> Unit = {}, onPlay: () -> Unit = {}, onAdd: () -> Unit = {}) {
        compose.setContent {
            AutoHMITheme {
                MusicPanel(state(), {}, onConnect, {}, onPlay, {}, {}, {}, {}, onAdd, {}, {})
            }
        }
    }
}
