package com.example.autohmi.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Playback lifetime is independent of the selected Compose screen or layout. */
class MusicSession(
    private val local: LocalMusicController,
    val spotify: SpotifyController,
    scope: CoroutineScope
) {
    private val source = MutableStateFlow(MusicSource.LOCAL)
    private var foreground = false
    val state = combine(source, local.state, spotify.state, spotify.connection, local.tracks) {
            selected, localPlayer, spotifyPlayer, connection, tracks ->
        MusicUiState(selected, if (selected == MusicSource.LOCAL) localPlayer else spotifyPlayer, connection, tracks)
    }.stateIn(scope, SharingStarted.Eagerly, MusicUiState())

    fun selectSource(selected: MusicSource) {
        if (selected == source.value) return
        controller().pause()
        source.value = selected
        spotify.setForeground(foreground && selected == MusicSource.SPOTIFY)
        // Selecting a source never starts playback or opens an authorization prompt.
    }

    fun connectSpotify() {
        selectSource(MusicSource.SPOTIFY)
        spotify.connect()
    }

    fun play() = controller().play()
    fun pause() = controller().pause()
    fun next() = controller().next()
    fun previous() = controller().previous()
    fun seekTo(positionMs: Long) = controller().seekTo(positionMs)
    fun playLocalTrack(id: String) {
        selectSource(MusicSource.LOCAL)
        local.playTrack(id)
    }

    fun setForeground(foreground: Boolean, changingConfiguration: Boolean = false) {
        this.foreground = foreground
        spotify.setForeground(foreground && source.value == MusicSource.SPOTIFY)
        if (!foreground && !changingConfiguration) local.pause()
    }

    fun release() {
        local.release()
        spotify.release()
    }

    private fun controller(): MusicController = if (source.value == MusicSource.LOCAL) local else spotify
}
