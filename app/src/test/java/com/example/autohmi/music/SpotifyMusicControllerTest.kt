package com.example.autohmi.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyMusicControllerTest {
    private val config = SpotifyConfiguration("0123456789abcdef0123456789abcdef", "https://example.com/spotify/callback")

    @Test
    fun invalidConfigurationRejectsMissingValuesAndInsecureRedirects() {
        assertTrue(config.isValid)
        listOf("", "http://example.com/callback", "https:///callback", "https://user@example.com/callback", "https://example.com/callback#fragment").forEach {
            assertFalse(config.copy(redirectUri = it).isValid)
        }
        assertFalse(config.copy(clientId = "not-a-client-id").isValid)
    }

    @Test
    fun missingConfigurationAndMissingAppDoNotStartAuthorization() = runTest {
        val remote = FakeSpotifyRemote()
        val missing = SpotifyMusicController(config.copy(clientId = ""), remote, backgroundScope)
        missing.setForeground(true)
        missing.connect()
        assertEquals(MusicIssue.CONFIGURATION, missing.connection.value.issue)
        remote.installed = false
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        assertEquals(MusicIssue.NOT_INSTALLED, controller.connection.value.issue)
        assertTrue(remote.attempts.isEmpty())
    }

    @Test
    fun connectRequiresForegroundAndRepeatedTapsCreateOnlyOneAttempt() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.connect()
        assertEquals(MusicIssue.NOT_IN_FOREGROUND, controller.connection.value.issue)
        controller.setForeground(true)
        repeat(5) { controller.connect() }
        assertEquals(1, remote.attempts.size)
        assertTrue(remote.authRequests.single())
        val session = FakeSpotifySession()
        remote.attempts.single().onConnected(session)
        controller.connect()
        assertEquals(1, remote.attempts.size)
        assertEquals(SpotifyConnectionStatus.CONNECTED, controller.connection.value.status)
    }

    @Test
    fun lateConnectionAfterDisconnectIsClosedAndCannotPublishState() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        controller.disconnect()
        val session = FakeSpotifySession()
        remote.attempts.single().onConnected(session)
        assertEquals(1, session.disconnects)
        assertEquals(SpotifyConnectionStatus.DISCONNECTED, controller.connection.value.status)
        assertNull(controller.state.value.track)
    }

    @Test
    fun backgroundCancelsSubscriptionAndQuietlyReconnectsOnReturn() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        val session = FakeSpotifySession()
        remote.attempts.single().onConnected(session)
        session.emit(playing("first"))
        controller.setForeground(false)
        assertEquals(1, session.cancellations)
        assertEquals(1, session.disconnects)
        assertEquals(SpotifyConnectionStatus.SUSPENDED, controller.connection.value.status)
        session.emit(playing("stale"))
        assertNull(controller.state.value.track)
        controller.setForeground(true)
        assertEquals(listOf(true, false), remote.authRequests)
        controller.disconnect()
        controller.setForeground(false)
        controller.setForeground(true)
        assertEquals(2, remote.attempts.size)
    }

    @Test
    fun failureIsActionableAndDoesNotLoopAuthorization() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        remote.attempts.single().onFailure(MusicIssue.NOT_LOGGED_IN)
        controller.setForeground(false)
        controller.setForeground(true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(MusicIssue.NOT_LOGGED_IN, controller.connection.value.issue)
        assertEquals(1, remote.attempts.size)
    }

    @Test
    fun timeoutRejectsALateSuccess() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(MusicIssue.CONNECTION_TIMEOUT, controller.connection.value.issue)
        val late = FakeSpotifySession()
        remote.attempts.single().onConnected(late)
        assertEquals(1, late.disconnects)
    }

    @Test
    fun commandsFollowObservedRestrictionsAndSeekIsClamped() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        val session = FakeSpotifySession()
        remote.attempts.single().onConnected(session)
        session.emit(playing("first").copy(canNext = false, canPrevious = false, canSeek = false))
        controller.next(); controller.previous(); controller.seekTo(10)
        assertTrue(session.commands.isEmpty())
        controller.pause()
        assertEquals(MusicCommand.PAUSE, session.commands.single().first)
        assertTrue(controller.state.value.isPlaying) // No optimistic state; await Spotify's event.
        session.emit(playing("first").copy(isPlaying = false, canPlay = true, canPause = false, canNext = true, canPrevious = true, canSeek = true))
        controller.play(); controller.next(); controller.previous(); controller.seekTo(-10); controller.seekTo(Long.MAX_VALUE)
        assertEquals(listOf(0L, 120_000L), session.commands.filter { it.first == MusicCommand.SEEK }.map { it.second })
    }

    @Test
    fun artworkCallbacksCannotOverwriteANewerTrack() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        val session = FakeSpotifySession()
        remote.attempts.single().onConnected(session)
        session.emit(playing("first"))
        session.emit(playing("second"))
        session.images.getValue("art-first")(byteArrayOf(1))
        assertNull(controller.state.value.artworkBytes)
        session.images.getValue("art-second")(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), controller.state.value.artworkBytes)
        controller.disconnect()
        session.images.getValue("art-second")(byteArrayOf(3))
        assertNull(controller.state.value.artworkBytes)
    }

    @Test
    fun commandFailureIsShownWithoutFakingSuccessfulPlayback() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        val session = FakeSpotifySession()
        remote.attempts.single().onConnected(session)
        session.emit(playing("first"))
        session.commandIssue = MusicIssue.COMMAND_FAILED
        controller.pause()
        assertEquals(MusicIssue.COMMAND_FAILED, controller.state.value.issue)
        assertTrue(controller.state.value.isPlaying)
        session.commandIssue = MusicIssue.CONNECTION_LOST
        controller.pause()
        assertEquals(SpotifyConnectionStatus.ERROR, controller.connection.value.status)
        assertNull(controller.state.value.track)
    }

    @Test
    fun sdkErrorsMapToActionableSafeCategories() {
        assertEquals(MusicIssue.NOT_INSTALLED, spotifyIssueFor("CouldNotFindSpotifyApp"))
        assertEquals(MusicIssue.NOT_LOGGED_IN, spotifyIssueFor("NotLoggedInException"))
        assertEquals(MusicIssue.NOT_AUTHORIZED, spotifyIssueFor("UserNotAuthorizedException"))
        assertEquals(MusicIssue.AUTHENTICATION, spotifyIssueFor("AuthenticationFailedException"))
        assertEquals(MusicIssue.OFFLINE, spotifyIssueFor("OfflineModeException"))
        assertEquals(MusicIssue.UNSUPPORTED, spotifyIssueFor("UnsupportedFeatureVersionException"))
        assertEquals(MusicIssue.CONNECTION_LOST, spotifyIssueFor("SpotifyConnectionTerminatedException"))
        assertEquals(MusicIssue.CONNECTION_FAILED, spotifyIssueFor("UnknownFailure"))
    }

    @Test
    fun subscriptionSetupFailureBecomesAnErrorAndReleasesTheSession() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        val session = FakeSpotifySession().apply { failSubscription = true }
        remote.attempts.single().onConnected(session)
        assertEquals(MusicIssue.CONNECTION_FAILED, controller.connection.value.issue)
        assertEquals(1, session.disconnects)
    }

    @Test
    fun cleanupFailureCannotPreventDisconnectOrLeaveStaleUiState() = runTest {
        val remote = FakeSpotifyRemote()
        val controller = SpotifyMusicController(config, remote, backgroundScope)
        controller.setForeground(true)
        controller.connect()
        val session = FakeSpotifySession().apply { failCancellation = true; failDisconnect = true }
        remote.attempts.single().onConnected(session)
        session.emit(playing("track"))
        controller.disconnect()
        assertEquals(1, session.cancellations)
        assertEquals(1, session.disconnects)
        assertNull(controller.state.value.track)
        assertEquals(SpotifyConnectionStatus.DISCONNECTED, controller.connection.value.status)
    }
}

internal fun playing(id: String) = MusicPlayerState(
    track = MusicTrack(id, "Track $id", "Artist", 120_000, "art-$id"),
    isPlaying = true,
    canPause = true
)

internal class FakeSpotifyRemote : SpotifyRemote {
    var installed = true
    val attempts = mutableListOf<SpotifyRemote.Listener>()
    val authRequests = mutableListOf<Boolean>()
    override fun isInstalled() = installed
    override fun connect(configuration: SpotifyConfiguration, showAuth: Boolean, listener: SpotifyRemote.Listener) {
        attempts += listener
        authRequests += showAuth
    }
}

internal class FakeSpotifySession : SpotifyRemote.Session {
    var disconnects = 0
    var cancellations = 0
    var commandIssue: MusicIssue? = null
    var failSubscription = false
    var failCancellation = false
    var failDisconnect = false
    val commands = mutableListOf<Pair<MusicCommand, Long>>()
    val images = mutableMapOf<String, (ByteArray?) -> Unit>()
    private var onState: (MusicPlayerState) -> Unit = {}
    override fun subscribe(onState: (MusicPlayerState) -> Unit, onError: (MusicIssue) -> Unit): () -> Unit {
        if (failSubscription) throw IllegalStateException("Test subscription failure")
        this.onState = onState
        return { cancellations++; if (failCancellation) throw IllegalStateException("Test cleanup failure") }
    }
    fun emit(state: MusicPlayerState) = onState(state)
    override fun artwork(key: String, onResult: (ByteArray?) -> Unit) { images[key] = onResult }
    override fun command(command: MusicCommand, positionMs: Long, onError: (MusicIssue) -> Unit) {
        commands += command to positionMs
        commandIssue?.let(onError)
    }
    override fun disconnect() { disconnects++; if (failDisconnect) throw IllegalStateException("Test disconnect failure") }
}
