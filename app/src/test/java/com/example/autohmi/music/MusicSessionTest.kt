package com.example.autohmi.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicSessionTest {
    @Test
    fun localPlaybackRemainsUsableWhenSpotifyIsUnconfigured() = runTest {
        val local = FakeLocalController()
        val spotify = SpotifyMusicController(SpotifyConfiguration("", ""), FakeSpotifyRemote(), backgroundScope)
        val session = MusicSession(local, spotify, backgroundScope)
        session.setForeground(true)
        session.connectSpotify()
        runCurrent()
        assertEquals(MusicIssue.CONFIGURATION, session.state.value.spotify.issue)
        session.playLocalTrack("local")
        runCurrent()
        assertEquals(MusicSource.LOCAL, session.state.value.source)
        assertTrue(session.state.value.player.isPlaying)
        assertEquals("local", session.state.value.player.track?.id)
    }

    @Test
    fun sourceSwitchPausesPreviousControllerAndDoesNotAutoplay() = runTest {
        val local = FakeLocalController()
        val spotify = SpotifyMusicController(SpotifyConfiguration("", ""), FakeSpotifyRemote(), backgroundScope)
        val session = MusicSession(local, spotify, backgroundScope)
        session.playLocalTrack("local")
        session.selectSource(MusicSource.SPOTIFY)
        runCurrent()
        assertFalse(local.state.value.isPlaying)
        session.selectSource(MusicSource.LOCAL)
        runCurrent()
        assertFalse(session.state.value.player.isPlaying)
        assertEquals("local", session.state.value.player.track?.id)
    }

    @Test
    fun uiUsesOnlyTheSelectedSourcesObservablePlayerState() = runTest {
        val local = FakeLocalController()
        val remote = FakeSpotifyRemote()
        val spotify = SpotifyMusicController(SpotifyConfiguration("0123456789abcdef0123456789abcdef", "https://example.com/callback"), remote, backgroundScope)
        val session = MusicSession(local, spotify, backgroundScope)
        session.setForeground(true)
        session.connectSpotify()
        val connection = FakeSpotifySession()
        remote.attempts.single().onConnected(connection)
        connection.emit(playing("spotify"))
        runCurrent()
        assertEquals("spotify", session.state.value.player.track?.id)
        local.state.value = playing("local")
        runCurrent()
        assertEquals("spotify", session.state.value.player.track?.id)
        session.selectSource(MusicSource.LOCAL)
        runCurrent()
        assertEquals("local", session.state.value.player.track?.id)
        assertEquals(MusicCommand.PAUSE, connection.commands.single().first)
    }

    @Test
    fun backgroundPausesLocalPlaybackButRecreationRetainsPlaybackState() = runTest {
        val local = FakeLocalController()
        val session = MusicSession(local, SpotifyMusicController(SpotifyConfiguration("", ""), FakeSpotifyRemote(), backgroundScope), backgroundScope)
        session.playLocalTrack("local")
        session.setForeground(false, changingConfiguration = true)
        assertTrue(local.state.value.isPlaying)
        session.setForeground(true)
        session.setForeground(false)
        assertFalse(local.state.value.isPlaying)
        session.release()
        assertTrue(local.released)
    }

    @Test
    fun timeFormattingClampsNegativeValuesAndHandlesLongTracks() {
        assertEquals("0:00", formatMusicTime(-1))
        assertEquals("1:05", formatMusicTime(65_999))
        assertEquals("120:00", formatMusicTime(7_200_000))
    }

    private class FakeLocalController : LocalMusicController {
        override val state = MutableStateFlow(MusicPlayerState())
        override val tracks = MutableStateFlow(listOf(MusicTrack("local", "Local song", durationMs = 120_000)))
        var released = false
        override fun setTracks(tracks: List<MusicTrack>) { this.tracks.value = tracks }
        override fun playTrack(id: String) { state.value = playing(id).copy(canPlay = false, canNext = false) }
        override fun play() { state.value = state.value.copy(isPlaying = true) }
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
        override fun release() { released = true }
    }
}
