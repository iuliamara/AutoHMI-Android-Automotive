package com.example.autohmi.music

import kotlinx.coroutines.flow.StateFlow

enum class MusicSource { LOCAL, SPOTIFY }

enum class MusicIssue {
    CONFIGURATION, NOT_INSTALLED, NOT_LOGGED_IN, NOT_AUTHORIZED, AUTHENTICATION,
    OFFLINE, UNSUPPORTED, CONNECTION_LOST, CONNECTION_FAILED, CONNECTION_TIMEOUT,
    COMMAND_FAILED, LOCAL_READ_FAILED, LOCAL_PLAYBACK_FAILED, NOT_IN_FOREGROUND
}

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0,
    val artworkKey: String? = null
)

data class MusicPlayerState(
    val track: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val artworkBytes: ByteArray? = null,
    val issue: MusicIssue? = null
)

interface MusicController {
    val state: StateFlow<MusicPlayerState>
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun release()
}

interface SpotifyController : MusicController {
    val connection: StateFlow<SpotifyConnectionState>
    fun connect(showAuth: Boolean = true)
    fun disconnect()
    fun setForeground(value: Boolean)
}

interface LocalMusicController : MusicController {
    val tracks: StateFlow<List<MusicTrack>>
    fun setTracks(tracks: List<MusicTrack>)
    fun playTrack(id: String)
}

enum class SpotifyConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, SUSPENDED, ERROR }

data class SpotifyConnectionState(
    val status: SpotifyConnectionStatus = SpotifyConnectionStatus.DISCONNECTED,
    val issue: MusicIssue? = null
)

data class MusicUiState(
    val source: MusicSource = MusicSource.LOCAL,
    val player: MusicPlayerState = MusicPlayerState(),
    val spotify: SpotifyConnectionState = SpotifyConnectionState(),
    val tracks: List<MusicTrack> = emptyList(),
    val isImporting: Boolean = false,
    val libraryIssue: MusicIssue? = null
)

fun formatMusicTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
