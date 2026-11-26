package com.example.autohmi.music

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

private val Context.musicLibraryStore by preferencesDataStore(name = "music_library")

data class LocalLibraryState(val tracks: List<MusicTrack>, val hasUnreadableFiles: Boolean = false)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalTrackLibrary(context: Context) {
    private val context = context.applicationContext
    private val trackUris = stringSetPreferencesKey("track_uris")

    val tracks = this.context.musicLibraryStore.data.mapLatest { preferences ->
        withContext(Dispatchers.IO) {
            val uris = preferences[trackUris].orEmpty()
            val tracks = uris.mapNotNull { uri -> runCatching { readTrack(uri.toUri()) }.getOrNull() }
            LocalLibraryState(tracks.sortedWith(compareBy({ it.title.lowercase() }, { it.id })), tracks.size != uris.size)
        }
    }

    suspend fun addTracks(uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        val valid = uris.distinct().filter { uri ->
            runCatching {
                require(uri.scheme == "content")
                readTrack(uri)
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
        }
        if (valid.isNotEmpty()) context.musicLibraryStore.edit { preferences ->
            preferences[trackUris] = preferences[trackUris].orEmpty() + valid.map(Uri::toString)
        }
        valid.size == uris.distinct().size
    }

    suspend fun artwork(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            withMetadataRetriever { retriever ->
                retriever.setDataSource(context, id.toUri())
                retriever.embeddedPicture?.takeIf { it.size <= 4 * 1024 * 1024 }
            }
        }.getOrNull()
    }

    private fun readTrack(uri: Uri): MusicTrack {
        var displayName = "Audio"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
        }
        return withMetadataRetriever { retriever ->
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0) { "The selected file has no readable audio duration" }
            MusicTrack(
                uri.toString(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: displayName,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                duration,
                uri.toString()
            )
        }
    }

    private inline fun <T> withMetadataRetriever(block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        return try { block(retriever) } finally { retriever.release() }
    }
}
