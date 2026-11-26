package com.example.autohmi.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autohmi.R
import com.example.autohmi.music.MusicSource
import com.example.autohmi.music.MusicUiState
import com.example.autohmi.music.web.SpotifyWebState
import com.example.autohmi.music.web.WebAuthStatus
import kotlin.math.abs

/** Navigation-only view. Visibility and size never start, stop or recreate a playback controller. */
@Composable
fun NavigationMusicPlayer(
    state: MusicUiState,
    web: SpotifyWebState,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    val player = state.player
    if (player.track == null && !player.isPlaying && !player.canPlay && !player.canPause) return
    val spotify = state.source == MusicSource.SPOTIFY
    val title = player.track?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.navigation_music_active_session)
    val artist = player.track?.artist.orEmpty()
    val device = web.playbackDevice?.takeIf { it.active }?.name?.takeIf(String::isNotBlank)
    val source = when {
        !spotify -> stringResource(R.string.navigation_music_local)
        web.stale -> stringResource(R.string.navigation_music_device_stale)
        device == null -> stringResource(R.string.navigation_music_device_unavailable)
        else -> stringResource(R.string.navigation_music_remote_source)
    }
    val stripLabel = when {
        !spotify -> stringResource(R.string.navigation_music_local_track, title)
        !web.stale && device != null -> stringResource(R.string.navigation_music_remote_device, device)
        else -> source
    }
    val controlsAvailable = !spotify || (web.auth == WebAuthStatus.AUTHORIZED && !web.busy && !web.stale)
    val toggleDescription = stringResource(if (collapsed) R.string.navigation_music_expand else R.string.navigation_music_collapse)

    Surface(Modifier.fillMaxWidth().animateContentSize(tween(220)).testTag("navigation_music_player")
        .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" }
        .musicBarSwipe(collapsed, onCollapsedChange), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp) {
        BoxWithConstraints {
            val wide = maxWidth >= 760.dp
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (collapsed) 2.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (collapsed) {
                    Icon(painterResource(R.drawable.ic_music), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stripLabel, Modifier.weight(1f).clickable(onClick = onOpen).heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically).testTag("navigation_music_strip_label"),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small).testTag("navigation_music_artwork"),
                        contentAlignment = Alignment.Center) { AlbumArtwork(player.artworkBytes, 64.dp) }
                    Column(Modifier.weight(1f).clickable(onClick = onOpen).heightIn(min = 48.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text(title, Modifier.fillMaxWidth().testTag("navigation_music_title"),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (artist.isNotBlank()) Text(artist, Modifier.fillMaxWidth().testTag("navigation_music_artist"),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!wide) Text(stripLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (wide) Column(Modifier.widthIn(max = 240.dp)) {
                        Text(source, Modifier.fillMaxWidth().testTag("navigation_music_source"),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (spotify && !web.stale && device != null) Text(device,
                            Modifier.fillMaxWidth().testTag("navigation_music_device"), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = onToggle, enabled = controlsAvailable && if (player.isPlaying) player.canPause else player.canPlay,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("navigation_music_toggle")) {
                    Text(stringResource(if (player.isPlaying) R.string.music_pause else R.string.music_play))
                }
                TextButton(onClick = onNext, enabled = controlsAvailable && player.canNext,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("navigation_music_next")) { Text(stringResource(R.string.music_next)) }
                FilledTonalIconButton(onClick = { onCollapsedChange(!collapsed) }, modifier = Modifier.size(48.dp)
                    .testTag("navigation_music_chevron").semantics { contentDescription = toggleDescription }) {
                    Text(if (collapsed) "⌃" else "⌄", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

/** The hit area is just the bar. Ignore taps, horizontal motion and multitouch. */
@Composable
private fun Modifier.musicBarSwipe(collapsed: Boolean, onChange: (Boolean) -> Unit): Modifier {
    val changeState by rememberUpdatedState(onChange)
    return pointerInput(collapsed) {
        val direction = if (collapsed) -1 else 1
        val threshold = 40.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var vertical = false
            var rejected = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) break
                val change = event.changes.first()
                if (change.id != down.id || change.isConsumed) break
                val delta = change.position - down.position
                if (!vertical && !rejected && maxOf(abs(delta.x), abs(delta.y)) > viewConfiguration.touchSlop) {
                    vertical = delta.y * direction > 0 && abs(delta.y) >= abs(delta.x) * 1.5f
                    rejected = !vertical
                }
                if (vertical) change.consume()
                if (!change.pressed) {
                    if (vertical && delta.y * direction >= threshold && abs(delta.y) >= abs(delta.x) * 1.5f) changeState(!collapsed)
                    break
                }
            }
        }
    }
}
