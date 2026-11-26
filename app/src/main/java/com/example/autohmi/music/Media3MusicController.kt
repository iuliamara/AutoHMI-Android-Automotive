package com.example.autohmi.music

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Media3MusicController(context: Context, scope: CoroutineScope) : LocalMusicController {
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    private val mediaSession = MediaSession.Builder(context.applicationContext, player).build()
    private val _state = MutableStateFlow(MusicPlayerState())
    override val state = _state.asStateFlow()
    private val _tracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    override val tracks = _tracks.asStateFlow()
    private var released = false

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publishState() }
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(isPlaying = false, issue = MusicIssue.LOCAL_PLAYBACK_FAILED)
            }
        })
        scope.launch {
            while (isActive && !released) {
                delay(500)
                if (player.isPlaying) publishState()
            }
        }
    }

    override fun setTracks(tracks: List<MusicTrack>) {
        require(tracks.all { it.id.toUri().scheme in setOf("content", "file") }) {
            "Media3 accepts selected local files only; Spotify playback belongs to the Spotify app"
        }
        if (released || tracks.map { it.id } == _tracks.value.map { it.id }) return
        val currentId = player.currentMediaItem?.mediaId
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        _tracks.value = tracks
        val index = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        player.setMediaItems(tracks.map { track ->
            MediaItem.Builder().setMediaId(track.id).setUri(track.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()).build()
        }, index, if (tracks.any { it.id == currentId }) position else 0)
        if (tracks.isNotEmpty()) player.prepare()
        player.playWhenReady = playing && tracks.any { it.id == currentId }
        publishState()
    }

    override fun playTrack(id: String) {
        val index = _tracks.value.indexOfFirst { it.id == id }
        if (released || index < 0) return
        player.seekTo(index, 0)
        player.prepare()
        player.play()
    }

    private fun publishState() {
        if (released) return
        val track = _tracks.value.firstOrNull { it.id == player.currentMediaItem?.mediaId }
        _state.value = MusicPlayerState(
            track = track,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceIn(0, track?.durationMs ?: 0),
            canPlay = track != null && !player.isPlaying,
            canPause = track != null && player.playWhenReady,
            canNext = player.hasNextMediaItem(),
            canPrevious = player.hasPreviousMediaItem() || player.currentPosition > 0,
            canSeek = track != null && player.isCurrentMediaItemSeekable,
            issue = if (player.playerError != null) MusicIssue.LOCAL_PLAYBACK_FAILED else null
        )
    }

    override fun play() {
        if (released || _tracks.value.isEmpty()) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }
    override fun pause() { if (!released) player.pause() }
    override fun next() { if (!released && player.hasNextMediaItem()) player.seekToNextMediaItem() }
    override fun previous() { if (!released) player.seekToPrevious() }
    override fun seekTo(positionMs: Long) {
        if (!released && _state.value.canSeek) player.seekTo(positionMs.coerceIn(0, _state.value.track?.durationMs ?: 0))
    }
    override fun release() {
        if (released) return
        released = true
        mediaSession.release()
        player.release()
    }
}
