package com.example.autohmi.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.autohmi.ui.usesExpandedLayout
import com.example.autohmi.ui.LocalExpandedWindow
import com.example.autohmi.music.MusicPlayerState
import com.example.autohmi.music.MusicSource
import com.example.autohmi.music.web.SpotifyWebState
import com.example.autohmi.music.web.WebAuthStatus
import com.example.autohmi.music.web.WebIssue

@Composable
fun SpotifyWebPanel(
    player: MusicPlayerState,
    web: SpotifyWebState,
    onLocal: () -> Unit,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onDevice: (String) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenTrack: () -> Unit,
    useQr: Boolean = false
) {
    val authorized = web.auth == WebAuthStatus.AUTHORIZED
    val enabled = !web.busy && !web.stale
    val observedPlayer = player.copy(canPlay = enabled && player.canPlay, canPause = enabled && player.canPause,
        canNext = enabled && player.canNext, canPrevious = enabled && player.canPrevious, canSeek = enabled && player.canSeek)
    BoxWithConstraints(Modifier.fillMaxSize()) {
    if (LocalExpandedWindow.current || usesExpandedLayout(maxWidth.value, maxHeight.value)) {
        MusicPanes(if (authorized) observedPlayer else MusicPlayerState(), MusicSource.SPOTIFY, "Spotify Connect",
            "Remote controls. Audio plays on the selected Spotify device.",
            sources = { SpotifySources(onLocal, Modifier.width(280.dp)) }, showControls = authorized,
            onPlay = onPlay, onPause = onPause, onNext = onNext, onPrevious = onPrevious, onSeek = onSeek,
            secondary = { modifier -> LazyColumn(modifier.testTag("spotify_web_list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { AuthorizationCard(web, onAuthorize, onCancel, onLogout, onRefresh, useQr) }
                if (authorized) {
                    deviceItems(web, onDevice)
                    item { SpotifyCredits(player, onOpenTrack) }
                }
            } })
    } else {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("spotify_web_list")) {
        item {
            Text("Spotify Connect", style = MaterialTheme.typography.headlineLarge)
            Text("Remote controls. Audio plays on the selected Spotify device.")
            SpotifySources(onLocal)
        }
        item {
            AuthorizationCard(web, onAuthorize, onCancel, onLogout, onRefresh, useQr)
        }
        if (authorized) {
            deviceItems(web, onDevice)
            item {
                NowPlaying(observedPlayer, MusicSource.SPOTIFY, onPlay, onPause, onNext, onPrevious, onSeek)
                SpotifyCredits(player, onOpenTrack)
            }
        }
    }
    }
    }
}

@Composable
private fun SpotifySources(onLocal: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(selected = false, onClick = onLocal, label = { Text("Local files") }, modifier = Modifier.heightIn(min = 48.dp))
        FilterChip(selected = true, onClick = {}, label = { Text("Spotify") }, modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable
private fun AuthorizationCard(web: SpotifyWebState, onAuthorize: () -> Unit, onCancel: () -> Unit, onLogout: () -> Unit, onRefresh: () -> Unit, useQr: Boolean) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (web.auth) {
                        WebAuthStatus.RESTORING -> "Restoring Spotify session…"
                        WebAuthStatus.SIGNED_OUT -> "Spotify not authorized"
                        WebAuthStatus.AUTHORIZING -> "Finish authorization in your browser"
                        WebAuthStatus.EXCHANGING -> "Completing authorization…"
                        WebAuthStatus.AUTHORIZED -> "Spotify authorized"
                    }, style = MaterialTheme.typography.titleMedium)
                    web.issue?.let { Text(webIssueMessage(it), color = MaterialTheme.colorScheme.error) }
                    when (web.auth) {
                        WebAuthStatus.SIGNED_OUT -> Button(onClick = onAuthorize, modifier = Modifier.heightIn(min = 64.dp)) { Text(if (useQr) "Connect Spotify" else "Authorize in browser") }
                        WebAuthStatus.AUTHORIZING -> OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel authorization") }
                        WebAuthStatus.AUTHORIZED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRefresh, enabled = !web.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Refresh devices") }
                            TextButton(onClick = onLogout, enabled = !web.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Log out of AutoHMI") }
                        }
                        else -> Unit
                    }
                    if (web.auth != WebAuthStatus.AUTHORIZED) Text(if (useQr) "Scan a QR with your phone to authorize. Spotify Android is not required on this display. Audio plays on your selected Spotify device." else "Spotify Android is not required. Authorize personally through Chrome; then open Spotify on your Windows computer.")
                }
            }
}

private fun LazyListScope.deviceItems(web: SpotifyWebState, onDevice: (String) -> Unit) {
            item {
                Text("Playback device", style = MaterialTheme.typography.titleLarge)
                Text("Playing on: ${web.playbackDevice?.name ?: "No active device"}")
                if (web.devices.isEmpty()) Text("No Spotify Connect devices found. Open Spotify on Windows, start a song or pause it there, then refresh devices.")
                if (web.stale) Text("Playback state is out of date. Controls will return after a successful refresh.")
                Text("Selecting a device transfers playback without starting audio. Start a queue in Spotify if none is available.")
            }
            items(web.devices, key = { it.id }) { device ->
                val selected = device.id == web.selectedDeviceId
                OutlinedButton(onClick = { onDevice(device.id) }, enabled = !web.busy && !device.restricted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("${if (selected) "Selected: " else "Use: "}${device.name} · ${device.type}${if (device.active) " · active" else ""}${if (device.restricted) " · restricted" else ""}")
                }
            }
}

@Composable
private fun SpotifyCredits(player: MusicPlayerState, onOpenTrack: () -> Unit) {
    if (player.track != null) TextButton(onClick = onOpenTrack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open this track in Spotify") }
    Text("Music and artwork supplied by Spotify.", style = MaterialTheme.typography.bodySmall)
}

fun webIssueMessage(issue: WebIssue): String = when (issue) {
    WebIssue.CONFIGURATION -> "Configure your Spotify Client ID and HTTPS callback locally, then rebuild."
    WebIssue.BROWSER_UNAVAILABLE -> "No system browser is available. Install or enable Chrome, then authorize again."
    WebIssue.AUTH_CANCELLED -> "Authorization was cancelled. You can try again."
    WebIssue.CALLBACK_INVALID -> "This authorization return could not be verified. Cancel and start a new browser authorization."
    WebIssue.AUTH_EXPIRED -> "Authorization expired. Start again from AutoHMI."
    WebIssue.AUTH_FAILED -> "Authorization could not be completed. Check the registered callback and try again."
    WebIssue.SESSION_EXPIRED -> "Your Spotify session expired or was revoked. Authorize again."
    WebIssue.STORAGE -> "Secure session storage is unavailable. Try logging out and authorizing again."
    WebIssue.NETWORK -> "Spotify could not be reached. Check your connection and refresh."
    WebIssue.ACCESS_DENIED -> "Spotify denied access. Check Premium, playback permissions and the app's allowed test users in Developer Dashboard."
    WebIssue.NO_DEVICE -> "Authorized, but no active Spotify playback device is available. Open Spotify on Windows and refresh devices."
    WebIssue.DEVICE_UNAVAILABLE -> "The playback device is unavailable or not ready. Open Spotify there, refresh devices, and select it again."
    WebIssue.RATE_LIMITED -> "Spotify asked AutoHMI to wait. Requests will resume after its retry interval."
    WebIssue.SERVICE -> "Spotify returned an unavailable or unexpected response. Try refreshing shortly."
    WebIssue.PLAYBACK_RESTRICTED -> "Spotify does not permit these controls on the selected device or content."
}
