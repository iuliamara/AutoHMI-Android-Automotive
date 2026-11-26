package com.example.autohmi.music.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyHttpTransport : SpotifyTransport {
    override suspend fun execute(request: SpotifyRequest): SpotifyResponse = withContext(Dispatchers.IO) {
        val uri = URI(request.url)
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null)
        require((uri.host == "accounts.spotify.com" && uri.path == "/api/token" && request.method == "POST") ||
            (uri.host == "api.spotify.com" && uri.path.startsWith("/v1/me/player")))
        val connection = URL(request.url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false // Never redirect tokens/Authorization headers to another origin.
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            request.bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val body = request.form?.entries?.joinToString("&") { "${SpotifyPkce.encode(it.key)}=${SpotifyPkce.encode(it.value)}" } ?: request.json
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (request.form != null) "application/x-www-form-urlencoded" else "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { readBounded(it, 1024 * 1024).toString(Charsets.UTF_8) }.orEmpty()
            SpotifyResponse(status, responseBody, connection.getHeaderField("Retry-After")?.toLongOrNull())
        } finally { connection.disconnect() }
    }

    override suspend fun artwork(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext null
        if (uri.scheme != "https" || uri.host != "i.scdn.co" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) return@withContext null
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            // Artwork is fetched without OAuth credentials or cookies.
            if (connection.responseCode != 200 || !connection.contentType.orEmpty().startsWith("image/")) return@withContext null
            connection.inputStream.use { readBounded(it, 3 * 1024 * 1024) }
        } finally { connection.disconnect() }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximum) throw IOException("Spotify response exceeded size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
