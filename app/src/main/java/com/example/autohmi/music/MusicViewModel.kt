package com.example.autohmi.music

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autohmi.spotifyWebRepository
import com.example.autohmi.createSpotifyPairing
import com.example.autohmi.music.web.WebIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LocalTrackLibrary(application)
    private val local = Media3MusicController(application, viewModelScope)
    val spotify = application.spotifyWebRepository()
    val pairing = application.createSpotifyPairing(viewModelScope)
    val session = MusicSession(local, spotify, viewModelScope)
    private val importing = MutableStateFlow(false)
    private val libraryIssue = MutableStateFlow<MusicIssue?>(null)
    private val localArtwork = MutableStateFlow<ByteArray?>(null)

    val state = combine(session.state, importing, libraryIssue, localArtwork) { state, loading, issue, artwork ->
        state.copy(
            isImporting = loading,
            libraryIssue = issue,
            player = if (state.source == MusicSource.LOCAL) state.player.copy(artworkBytes = artwork) else state.player
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MusicUiState())

    init {
        viewModelScope.launch {
            library.tracks.retryWhen { cause, _ ->
                if (cause is CancellationException) throw cause
                libraryIssue.value = MusicIssue.LOCAL_READ_FAILED
                delay(2_000)
                true
            }.collect {
                local.setTracks(it.tracks)
                libraryIssue.value = if (it.hasUnreadableFiles) MusicIssue.LOCAL_READ_FAILED else null
            }
        }
        viewModelScope.launch {
            local.state.map { it.track?.id }.distinctUntilChanged().collect { id ->
                localArtwork.value = null
                if (id != null) {
                    val artwork = library.artwork(id)
                    if (local.state.value.track?.id == id) localArtwork.value = artwork
                }
            }
        }
    }

    fun onStart() {
        session.setForeground(true)
        pairing.setForeground(true)
    }

    fun onStop(changingConfiguration: Boolean) {
        session.setForeground(false, changingConfiguration)
        pairing.setForeground(false)
    }

    fun authorizeSpotify(openBrowser: (String) -> Boolean) {
        session.selectSource(MusicSource.SPOTIFY)
        viewModelScope.launch {
            pairing.cancelAndJoin()
            val url = spotify.prepareAuthorization() ?: return@launch
            if (!openBrowser(url)) spotify.cancelAuthorization(WebIssue.BROWSER_UNAVAILABLE)
        }
    }

    fun connectSpotifyQr() { session.selectSource(MusicSource.SPOTIFY); pairing.connect() }
    fun cancelSpotifyAuthorization() = viewModelScope.launch {
        pairing.cancelAndJoin()
        spotify.cancelAuthorization().join()
    }
    fun logoutSpotify() = viewModelScope.launch { pairing.cancelAndJoin(); spotify.logout().join() }

    fun addTracks(uris: List<Uri>) {
        if (uris.isEmpty() || importing.value) return
        importing.value = true
        libraryIssue.value = null
        viewModelScope.launch {
            try {
                if (!library.addTracks(uris)) libraryIssue.value = MusicIssue.LOCAL_READ_FAILED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                libraryIssue.value = MusicIssue.LOCAL_READ_FAILED
            } finally { importing.value = false }
        }
    }

    override fun onCleared() {
        pairing.close()
        session.release()
    }
}
