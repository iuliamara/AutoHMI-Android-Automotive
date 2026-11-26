package com.example.autohmi.music

import android.net.Uri
import androidx.compose.material3.Text
import com.example.autohmi.createDisplayContentRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun media3PlaysSelectedLocalAudioAndSupportsQueueSeekAndPause() {
        compose.setContent { Text("Local playback integration test") }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = listOf(createWave(context.cacheDir), createWave(context.cacheDir))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: Media3MusicController
        var playerCreated = false
        try {
            instrumentation.runOnMainSync {
                player = Media3MusicController(context, scope)
                playerCreated = true
                player.setTracks(files.mapIndexed { index, file -> MusicTrack(Uri.fromFile(file).toString(), "Test tone $index", "Generated test fixture", 5_000) })
                player.play()
            }
            compose.waitUntil(10_000) { player.state.value.isPlaying }
            assertEquals("Test tone 0", player.state.value.track?.title)
            instrumentation.runOnMainSync { player.next() }
            compose.waitUntil(5_000) { player.state.value.track?.title == "Test tone 1" }
            instrumentation.runOnMainSync { player.previous() }
            compose.waitUntil(5_000) { player.state.value.track?.title == "Test tone 0" }
            instrumentation.runOnMainSync { player.seekTo(1_000); player.pause() }
            compose.waitUntil(5_000) { !player.state.value.isPlaying && player.state.value.positionMs >= 1_000 }
            assertTrue(player.state.value.positionMs < 2_000)
            assertTrue(player.state.value.canPlay)
            instrumentation.runOnMainSync {
                try {
                    player.setTracks(listOf(MusicTrack("spotify:track:example", "Not local")))
                    fail("Spotify URIs must never reach ExoPlayer")
                } catch (_: IllegalArgumentException) { /* Expected local-only guard. */ }
            }
        } finally {
            instrumentation.runOnMainSync { if (playerCreated) player.release() }
            scope.cancel()
            files.forEach(File::delete)
        }
    }

    @Test
    fun installedAppDetectionProducesMissingAppErrorWithoutStartingAuthentication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val remote = SpotifySdkRemote(context, scope)
        org.junit.Assume.assumeFalse("Missing-app test requires Spotify to be absent", remote.isInstalled())
        val controller = SpotifyMusicController(SpotifyConfiguration("0123456789abcdef0123456789abcdef", "https://example.com/callback"), remote, scope)
        try {
            instrumentation.runOnMainSync { controller.setForeground(true); controller.connect() }
            assertEquals(MusicIssue.NOT_INSTALLED, controller.connection.value.issue)
            assertEquals(SpotifyConnectionStatus.ERROR, controller.connection.value.status)
        } finally { controller.release(); scope.cancel() }
    }

    private fun createWave(directory: File): File {
        val file = File(directory, "autohmi-audio-test-${UUID.randomUUID()}.wav")
        val sampleRate = 8_000
        val sampleCount = sampleRate * 5
        val dataSize = sampleCount * 2
        FileOutputStream(file).use { stream ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(sampleRate)
                .putInt(sampleRate * 2).putShort(2.toShort()).putShort(16.toShort())
                .put("data".toByteArray()).putInt(dataSize)
            stream.write(header.array())
            val samples = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            repeat(sampleCount) { index -> samples.putShort((kotlin.math.sin(2 * Math.PI * 220 * index / sampleRate) * 200).toInt().toShort()) }
            stream.write(samples.array())
        }
        return file
    }
}
