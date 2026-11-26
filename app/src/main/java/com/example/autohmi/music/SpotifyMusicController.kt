package com.example.autohmi.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpotifyConfiguration(val clientId: String, val redirectUri: String) {
    val isValid: Boolean
        get() = clientId.matches(Regex("[a-fA-F0-9]{32}")) && SpotifyCallbackTarget.parse(redirectUri) != null
}

enum class MusicCommand { PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

fun spotifyIssueFor(errorName: String): MusicIssue = when (errorName) {
    "CouldNotFindSpotifyApp" -> MusicIssue.NOT_INSTALLED
    "NotLoggedInException" -> MusicIssue.NOT_LOGGED_IN
    "UserNotAuthorizedException" -> MusicIssue.NOT_AUTHORIZED
    "AuthenticationFailedException", "SecurityException" -> MusicIssue.AUTHENTICATION
    "OfflineModeException" -> MusicIssue.OFFLINE
    "UnsupportedFeatureVersionException" -> MusicIssue.UNSUPPORTED
    "SpotifyDisconnectedException", "SpotifyConnectionTerminatedException" -> MusicIssue.CONNECTION_LOST
    else -> MusicIssue.CONNECTION_FAILED
}

interface SpotifyRemote {
    fun isInstalled(): Boolean
    fun connect(configuration: SpotifyConfiguration, showAuth: Boolean, listener: Listener)

    interface Listener {
        fun onConnected(session: Session)
        fun onFailure(issue: MusicIssue)
    }

    interface Session {
        fun subscribe(onState: (MusicPlayerState) -> Unit, onError: (MusicIssue) -> Unit): () -> Unit
        fun artwork(key: String, onResult: (ByteArray?) -> Unit)
        fun command(command: MusicCommand, positionMs: Long, onError: (MusicIssue) -> Unit)
        fun disconnect()
    }
}

class SpotifyMusicController(
    private val configuration: SpotifyConfiguration,
    private val remote: SpotifyRemote,
    private val scope: CoroutineScope
) : SpotifyController {
    private val _state = MutableStateFlow(MusicPlayerState())
    override val state = _state.asStateFlow()
    private val _connection = MutableStateFlow(SpotifyConnectionState(
        issue = if (configuration.isValid) null else MusicIssue.CONFIGURATION
    ))
    override val connection = _connection.asStateFlow()

    private var session: SpotifyRemote.Session? = null
    private var unsubscribe: (() -> Unit)? = null
    private var timeout: Job? = null
    private var generation = 0L
    private var foreground = false
    private var wantsConnection = false

    override fun setForeground(value: Boolean) {
        foreground = value
        if (!value && wantsConnection) dropConnection(SpotifyConnectionStatus.SUSPENDED)
        else if (value && wantsConnection && _connection.value.status == SpotifyConnectionStatus.SUSPENDED) {
            connect(showAuth = false)
        }
    }

    override fun connect(showAuth: Boolean) {
        if (_connection.value.status in setOf(SpotifyConnectionStatus.CONNECTING, SpotifyConnectionStatus.CONNECTED)) return
        val issue = when {
            !configuration.isValid -> MusicIssue.CONFIGURATION
            !remote.isInstalled() -> MusicIssue.NOT_INSTALLED
            !foreground -> MusicIssue.NOT_IN_FOREGROUND
            else -> null
        }
        if (issue != null) {
            _connection.value = SpotifyConnectionState(SpotifyConnectionStatus.ERROR, issue)
            return
        }
        wantsConnection = true
        val attempt = ++generation
        _connection.value = SpotifyConnectionState(SpotifyConnectionStatus.CONNECTING)
        timeout?.cancel()
        timeout = scope.launch {
            delay(20_000)
            if (attempt == generation) fail(MusicIssue.CONNECTION_TIMEOUT)
        }
        try {
            remote.connect(configuration, showAuth, object : SpotifyRemote.Listener {
                override fun onConnected(session: SpotifyRemote.Session) {
                    if (attempt != generation || !foreground || !wantsConnection) {
                        runCatching { session.disconnect() }
                        return
                    }
                    timeout?.cancel()
                    this@SpotifyMusicController.session = session
                    _connection.value = SpotifyConnectionState(SpotifyConnectionStatus.CONNECTED)
                    val cancelSubscription = try { session.subscribe(
                        onState = { player ->
                            if (attempt == generation) {
                                val old = _state.value
                                val key = player.track?.artworkKey
                                _state.value = player.copy(
                                    artworkBytes = if (key == old.track?.artworkKey) old.artworkBytes else null
                                )
                                if (key != null && key != old.track?.artworkKey) {
                                    runCatching { session.artwork(key) { bytes ->
                                        if (attempt == generation && _state.value.track?.artworkKey == key) {
                                            _state.update { it.copy(artworkBytes = bytes) }
                                        }
                                    } }
                                }
                            }
                        },
                        onError = { if (attempt == generation) fail(it) }
                    ) } catch (_: Exception) {
                        if (attempt == generation) fail(MusicIssue.CONNECTION_FAILED)
                        return
                    }
                    if (attempt == generation) unsubscribe = cancelSubscription else runCatching { cancelSubscription() }
                }

                override fun onFailure(issue: MusicIssue) {
                    if (attempt == generation) fail(issue)
                }
            })
        } catch (_: Exception) {
            if (attempt == generation) fail(MusicIssue.CONNECTION_FAILED)
        }
    }

    override fun disconnect() {
        wantsConnection = false
        dropConnection(SpotifyConnectionStatus.DISCONNECTED)
    }

    private fun fail(issue: MusicIssue) {
        // Reconnection after a failure requires user intent; never reopen consent in a loop.
        wantsConnection = false
        dropConnection(SpotifyConnectionStatus.ERROR, issue)
    }

    private fun dropConnection(status: SpotifyConnectionStatus, issue: MusicIssue? = null) {
        generation++
        timeout?.cancel()
        timeout = null
        val cancelSubscription = unsubscribe
        unsubscribe = null
        val previousSession = session
        session = null
        runCatching { cancelSubscription?.invoke() }
        runCatching { previousSession?.disconnect() }
        _state.value = MusicPlayerState()
        _connection.value = SpotifyConnectionState(status, issue)
    }

    private fun send(command: MusicCommand, allowed: Boolean, positionMs: Long = 0) {
        if (!allowed) return
        val currentSession = session ?: return
        val attempt = generation
        try {
            currentSession.command(command, positionMs) { issue ->
                if (attempt == generation) {
                    if (issue == MusicIssue.CONNECTION_LOST) fail(issue)
                    else _state.update { it.copy(issue = issue) }
                }
            }
        } catch (_: Exception) {
            _state.update { it.copy(issue = MusicIssue.COMMAND_FAILED) }
        }
    }

    override fun play() = send(MusicCommand.PLAY, _state.value.canPlay)
    override fun pause() = send(MusicCommand.PAUSE, _state.value.canPause)
    override fun next() = send(MusicCommand.NEXT, _state.value.canNext)
    override fun previous() = send(MusicCommand.PREVIOUS, _state.value.canPrevious)
    override fun seekTo(positionMs: Long) = send(
        MusicCommand.SEEK, _state.value.canSeek,
        positionMs.coerceIn(0, _state.value.track?.durationMs?.coerceAtLeast(0) ?: 0)
    )
    override fun release() = disconnect()
}
