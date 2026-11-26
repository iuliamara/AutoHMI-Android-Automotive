package com.example.autohmi.music

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.ImageUri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpotifySdkRemote(context: Context, private val scope: CoroutineScope) : SpotifyRemote {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var activity: Activity? = null

    fun attach(activity: Activity?) { this.activity = activity }
    override fun isInstalled(): Boolean = SpotifyAppRemote.isSpotifyInstalled(appContext)

    override fun connect(configuration: SpotifyConfiguration, showAuth: Boolean, listener: SpotifyRemote.Listener) {
        val host = activity ?: return listener.onFailure(MusicIssue.NOT_IN_FOREGROUND)
        SpotifyAppRemote.connect(
            host,
            ConnectionParams.Builder(configuration.clientId)
                .setRedirectUri(configuration.redirectUri)
                .showAuthView(showAuth)
                .build(),
            object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    main.post { listener.onConnected(SdkSession(remote)) }
                }
                override fun onFailure(error: Throwable) {
                    main.post { listener.onFailure(mapIssue(error)) }
                }
            }
        )
    }

    private fun mapIssue(error: Throwable): MusicIssue {
        return generateSequence(error) { it.cause }.take(8)
            .map { spotifyIssueFor(it.javaClass.simpleName) }
            .firstOrNull { it != MusicIssue.CONNECTION_FAILED } ?: MusicIssue.CONNECTION_FAILED
    }

    private inner class SdkSession(private val remote: SpotifyAppRemote) : SpotifyRemote.Session {
        override fun subscribe(onState: (MusicPlayerState) -> Unit, onError: (MusicIssue) -> Unit): () -> Unit {
            val subscription = remote.playerApi.subscribeToPlayerState()
            subscription.setEventCallback { player ->
                val track = player.track
                val restrictions = player.playbackRestrictions
                val state = MusicPlayerState(
                    track = track?.let {
                        MusicTrack(it.uri, it.name.orEmpty(), it.artist?.name.orEmpty(), it.duration.coerceAtLeast(0), it.imageUri?.raw)
                    },
                    isPlaying = track != null && !player.isPaused,
                    positionMs = player.playbackPosition.coerceIn(0, track?.duration?.coerceAtLeast(0) ?: 0),
                    canPlay = track != null && player.isPaused,
                    canPause = track != null && !player.isPaused,
                    canNext = track != null && restrictions?.canSkipNext == true,
                    canPrevious = track != null && restrictions?.canSkipPrev == true,
                    canSeek = track != null && restrictions?.canSeek == true
                )
                main.post { onState(state) }
            }
            subscription.setErrorCallback { error -> main.post { onError(mapIssue(error)) } }
            return { subscription.cancel() }
        }

        override fun artwork(key: String, onResult: (ByteArray?) -> Unit) {
            remote.imagesApi.getImage(ImageUri(key)).setResultCallback { bitmap ->
                scope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        ByteArrayOutputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            output.toByteArray()
                        }
                    }
                    onResult(bytes)
                }
            }.setErrorCallback { main.post { onResult(null) } }
        }

        override fun command(command: MusicCommand, positionMs: Long, onError: (MusicIssue) -> Unit) {
            val result = when (command) {
                MusicCommand.PLAY -> remote.playerApi.resume()
                MusicCommand.PAUSE -> remote.playerApi.pause()
                MusicCommand.NEXT -> remote.playerApi.skipNext()
                MusicCommand.PREVIOUS -> remote.playerApi.skipPrevious()
                MusicCommand.SEEK -> remote.playerApi.seekTo(positionMs)
            }
            result.setErrorCallback { error ->
                main.post {
                    val issue = mapIssue(error)
                    onError(if (issue == MusicIssue.CONNECTION_FAILED) MusicIssue.COMMAND_FAILED else issue)
                }
            }
        }

        override fun disconnect() { SpotifyAppRemote.disconnect(remote) }
    }
}
