package com.example.autohmi.music.ui

import android.content.Intent
import android.content.pm.PackageManager
import com.example.autohmi.music.pairing.PairingPhase
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.example.autohmi.ui.usesExpandedLayout
import com.example.autohmi.ui.LocalExpandedWindow
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autohmi.music.web.SpotifySystemBrowser
import com.example.autohmi.R
import com.example.autohmi.music.MusicIssue
import com.example.autohmi.music.MusicPlayerState
import com.example.autohmi.music.MusicSource
import com.example.autohmi.music.MusicUiState
import com.example.autohmi.music.MusicViewModel
import com.example.autohmi.music.SpotifyConnectionStatus
import com.example.autohmi.music.formatMusicTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MusicScreen(state: MusicUiState, viewModel: MusicViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel::addTracks)
    val context = LocalContext.current
    val web by viewModel.spotify.web.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.state.collectAsStateWithLifecycle()
    val automotive = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) }
    if (state.source == MusicSource.SPOTIFY) {
        if (automotive && pairing.phase !in setOf(PairingPhase.IDLE, PairingPhase.CANCELLED)) {
            SpotifyPairingPanel(pairing, viewModel::connectSpotifyQr, { viewModel.pairing.cancel() })
            return
        }
        SpotifyWebPanel(state.player, web,
            onLocal = { viewModel.pairing.cancel(); viewModel.session.selectSource(MusicSource.LOCAL) },
            onAuthorize = { if (automotive) viewModel.connectSpotifyQr() else viewModel.authorizeSpotify { SpotifySystemBrowser.open(context, it) } },
            onCancel = { viewModel.cancelSpotifyAuthorization() },
            onLogout = { viewModel.logoutSpotify() },
            onRefresh = { viewModel.spotify.refreshNow() },
            onDevice = { viewModel.spotify.selectDevice(it) },
            onPlay = viewModel.session::play, onPause = viewModel.session::pause,
            onNext = viewModel.session::next, onPrevious = viewModel.session::previous, onSeek = viewModel.session::seekTo,
            onOpenTrack = {
                val match = Regex("spotify:(track|episode):([A-Za-z0-9]{1,64})").matchEntire(state.player.track?.id.orEmpty())
                if (match != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                    "https://open.spotify.com/${match.groupValues[1]}/${match.groupValues[2]}".toUri())) }
            }, useQr = automotive)
        return
    }
    MusicPanel(
        state,
        onSource = viewModel.session::selectSource,
        onConnect = viewModel.session::connectSpotify,
        onDisconnect = viewModel.session.spotify::disconnect,
        onPlay = viewModel.session::play,
        onPause = viewModel.session::pause,
        onNext = viewModel.session::next,
        onPrevious = viewModel.session::previous,
        onSeek = viewModel.session::seekTo,
        onAddTracks = { picker.launch(arrayOf("audio/*")) },
        onTrack = viewModel.session::playLocalTrack,
        onOpenSpotify = {
            val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                ?: Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.spotify.music".toUri())
            runCatching { context.startActivity(intent) }
        }
    )
}

@Composable
fun MusicPanel(
    state: MusicUiState,
    onSource: (MusicSource) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onAddTracks: () -> Unit,
    onTrack: (String) -> Unit,
    onOpenSpotify: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    if (LocalExpandedWindow.current || usesExpandedLayout(maxWidth.value, maxHeight.value)) {
        MusicPanes(state.player, state.source, stringResource(R.string.music), stringResource(R.string.music_description),
            sources = { SourceSelector(state.source, onSource, Modifier.width(280.dp)) },
            onPlay = onPlay, onPause = onPause, onNext = onNext, onPrevious = onPrevious, onSeek = onSeek,
            secondary = { modifier ->
                LazyColumn(modifier.testTag("music_list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (state.source == MusicSource.SPOTIFY) item { LegacyConnection(state, onConnect, onDisconnect, onOpenSpotify) }
                    else localLibraryItems(state, onAddTracks, onTrack)
                }
            })
    } else {
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("music_list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(stringResource(R.string.music), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.music_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SourceSelector(state.source, onSource)
        }
        if (state.source == MusicSource.SPOTIFY) {
            item {
                LegacyConnection(state, onConnect, onDisconnect, onOpenSpotify)
            }
        }
        item { NowPlaying(state.player, state.source, onPlay, onPause, onNext, onPrevious, onSeek) }
        if (state.source == MusicSource.LOCAL) localLibraryItems(state, onAddTracks, onTrack)
    }
    }
    }
}

@Composable
private fun SourceSelector(source: MusicSource, onSource: (MusicSource) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicSource.entries.forEach { item -> FilterChip(selected = source == item, onClick = { onSource(item) },
            label = { Text(stringResource(if (item == MusicSource.LOCAL) R.string.local_files else R.string.spotify)) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
    }
}

@Composable
private fun LegacyConnection(state: MusicUiState, onConnect: () -> Unit, onDisconnect: () -> Unit, onOpenSpotify: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(connectionLabel(state.spotify.status)), style = MaterialTheme.typography.titleMedium)
            state.spotify.issue?.let { Text(stringResource(issueLabel(it)), color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val active = state.spotify.status in setOf(SpotifyConnectionStatus.CONNECTED, SpotifyConnectionStatus.CONNECTING, SpotifyConnectionStatus.SUSPENDED)
                Button(onClick = if (active) onDisconnect else onConnect, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(if (active) R.string.disconnect_spotify else R.string.connect_spotify)) }
                TextButton(onClick = onOpenSpotify, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.open_spotify)) }
            }
            Text(stringResource(R.string.spotify_playback_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun LazyListScope.localLibraryItems(state: MusicUiState, onAddTracks: () -> Unit, onTrack: (String) -> Unit) {
            item {
                Button(onClick = onAddTracks, enabled = !state.isImporting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.isImporting) R.string.importing_tracks else R.string.add_local_tracks))
                }
                Text(stringResource(R.string.local_playback_note), style = MaterialTheme.typography.bodySmall)
                state.libraryIssue?.let { Text(stringResource(issueLabel(it)), color = MaterialTheme.colorScheme.error) }
            }
            if (state.tracks.isEmpty()) item { Text(stringResource(R.string.local_music_empty)) }
            items(state.tracks, key = { it.id }) { track ->
                Card(Modifier.fillMaxWidth().clickable { onTrack(track.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleMedium)
                        Text(track.artist.ifBlank { stringResource(R.string.unknown_artist) })
                        Text(formatMusicTime(track.durationMs), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
}

@Composable
internal fun NowPlaying(player: MusicPlayerState, source: MusicSource, onPlay: () -> Unit, onPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackDetails(player, source, 144.dp)
            PlaybackControlsContent(player, source, onPlay, onPause, onNext, onPrevious, onSeek, false)
        }
    }
}

@Composable
internal fun MusicPanes(
    player: MusicPlayerState, source: MusicSource, title: String, description: String,
    sources: @Composable () -> Unit, showControls: Boolean = true,
    onPlay: () -> Unit, onPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit,
    secondary: @Composable (Modifier) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("music_two_pane"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sources()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val artworkSize = (maxHeight - 180.dp).coerceIn(96.dp, 240.dp)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Card(Modifier.weight(0.9f).fillMaxHeight().testTag("track_identity")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrackDetails(player, source, artworkSize)
                }
            }
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showControls) Card(Modifier.fillMaxWidth().testTag("playback_controls")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaybackControlsContent(player, source, onPlay, onPause, onNext, onPrevious, onSeek, true)
                    }
                }
                secondary(Modifier.fillMaxWidth().weight(1f))
            }
        }
        }
    }
}

@Composable
private fun TrackDetails(player: MusicPlayerState, source: MusicSource, artworkSize: Dp) {
    Text(stringResource(if (source == MusicSource.SPOTIFY) R.string.spotify else R.string.local_files), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    AlbumArtwork(player.artworkBytes, artworkSize)
    Text(player.track?.title ?: stringResource(R.string.nothing_playing), style = MaterialTheme.typography.titleLarge)
    player.track?.let { Text(it.artist.ifBlank { stringResource(R.string.unknown_artist) }, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun PlaybackControlsContent(player: MusicPlayerState, source: MusicSource, onPlay: () -> Unit, onPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, expanded: Boolean) {
            val duration = player.track?.durationMs ?: 0
            val controlPadding = PaddingValues(horizontal = if (expanded) 24.dp else 8.dp, vertical = 8.dp)
            var dragPosition by remember(player.track?.id) { mutableFloatStateOf(-1f) }
            val seekDescription = stringResource(R.string.seek_position)
            Slider(
                value = if (dragPosition >= 0) dragPosition else player.positionMs.coerceIn(0, duration.coerceAtLeast(0)).toFloat(),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = { if (dragPosition >= 0) onSeek(dragPosition.toLong()); dragPosition = -1f },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = player.canSeek,
                modifier = Modifier.semantics { contentDescription = seekDescription }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMusicTime(if (dragPosition >= 0) dragPosition.toLong() else player.positionMs))
                Text(formatMusicTime(duration))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPrevious, enabled = player.canPrevious, contentPadding = controlPadding, modifier = Modifier.weight(1f).heightIn(min = if (expanded) 64.dp else 48.dp)) {
                    Text(stringResource(R.string.music_previous))
                }
                Button(onClick = if (player.isPlaying) onPause else onPlay, enabled = if (player.isPlaying) player.canPause else player.canPlay, contentPadding = controlPadding, modifier = Modifier.weight(1f).heightIn(min = if (expanded) 64.dp else 48.dp)) {
                    Text(stringResource(if (player.isPlaying) R.string.music_pause else R.string.music_play))
                }
                OutlinedButton(onClick = onNext, enabled = player.canNext, contentPadding = controlPadding, modifier = Modifier.weight(1f).heightIn(min = if (expanded) 64.dp else 48.dp)) {
                    Text(stringResource(R.string.music_next))
                }
            }
            player.issue?.let { Text(stringResource(issueLabel(it)), color = MaterialTheme.colorScheme.error) }
            if (source == MusicSource.SPOTIFY && player.track == null) Text(stringResource(R.string.spotify_start_in_app), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AlbumArtwork(bytes: ByteArray?, size: Dp = 144.dp) {
    val image by produceState<ImageBitmap?>(null, bytes) {
        value = null
        value = withContext(Dispatchers.Default) {
            if (bytes == null) null else runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (image != null) Image(image!!, stringResource(R.string.album_artwork), Modifier.size(size), contentScale = ContentScale.Crop)
    else if (size <= 144.dp) Icon(painterResource(R.drawable.ic_music), stringResource(R.string.album_artwork_placeholder), Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
    else Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_music), stringResource(R.string.album_artwork_placeholder), Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun CompactMusicPlayer(state: MusicUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit = {}, onPrevious: () -> Unit = {}, expanded: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 0.dp)) {
            Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp)) {
                Text(state.player.track?.title ?: stringResource(R.string.nothing_playing), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (expanded && !state.player.track?.artist.isNullOrBlank()) state.player.track!!.artist else stringResource(if (state.source == MusicSource.SPOTIFY) R.string.spotify else R.string.local_files), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) OutlinedButton(onClick = onPrevious, enabled = state.player.canPrevious, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.music_previous)) }
            TextButton(onClick = onToggle, enabled = if (state.player.isPlaying) state.player.canPause else state.player.canPlay, modifier = Modifier.heightIn(min = if (expanded) 64.dp else 48.dp)) {
                Text(stringResource(if (state.player.isPlaying) R.string.music_pause else R.string.music_play))
            }
            if (expanded) OutlinedButton(onClick = onNext, enabled = state.player.canNext, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.music_next)) }
        }
    }
}

private fun connectionLabel(status: SpotifyConnectionStatus): Int = when (status) {
    SpotifyConnectionStatus.DISCONNECTED -> R.string.spotify_disconnected
    SpotifyConnectionStatus.CONNECTING -> R.string.spotify_connecting
    SpotifyConnectionStatus.CONNECTED -> R.string.spotify_connected
    SpotifyConnectionStatus.SUSPENDED -> R.string.spotify_suspended
    SpotifyConnectionStatus.ERROR -> R.string.spotify_connection_error
}

private fun issueLabel(issue: MusicIssue): Int = when (issue) {
    MusicIssue.CONFIGURATION -> R.string.spotify_configuration_error
    MusicIssue.NOT_INSTALLED -> R.string.spotify_not_installed
    MusicIssue.NOT_LOGGED_IN -> R.string.spotify_not_logged_in
    MusicIssue.NOT_AUTHORIZED -> R.string.spotify_not_authorized
    MusicIssue.AUTHENTICATION -> R.string.spotify_authentication_error
    MusicIssue.OFFLINE -> R.string.spotify_offline
    MusicIssue.UNSUPPORTED -> R.string.spotify_unsupported
    MusicIssue.CONNECTION_LOST -> R.string.spotify_connection_lost
    MusicIssue.CONNECTION_FAILED -> R.string.spotify_connection_failed
    MusicIssue.CONNECTION_TIMEOUT -> R.string.spotify_connection_timeout
    MusicIssue.COMMAND_FAILED -> R.string.spotify_command_failed
    MusicIssue.LOCAL_READ_FAILED -> R.string.local_read_error
    MusicIssue.LOCAL_PLAYBACK_FAILED -> R.string.local_playback_error
    MusicIssue.NOT_IN_FOREGROUND -> R.string.spotify_foreground_error
}
