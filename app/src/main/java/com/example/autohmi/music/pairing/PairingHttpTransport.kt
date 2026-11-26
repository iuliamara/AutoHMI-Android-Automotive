package com.example.autohmi.music.pairing

import com.example.autohmi.music.web.SpotifyRequest
import com.example.autohmi.music.web.SpotifyResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Origin pinned. No redirects, cookies, request logging or permissive TLS fallback. */
class PairingHttpTransport(private val configuration: PairingConfiguration) : PairingTransport {
    override suspend fun execute(request: SpotifyRequest): SpotifyResponse = withContext(Dispatchers.IO) {
        val uri = URI(request.url)
        require(configuration.isConfigured && request.method == "POST" && uri.scheme == "https" &&
            uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null &&
            "https://${uri.host}" == configuration.origin &&
            (uri.path == "/v1/pairings" || uri.path.matches(Regex("/v1/pairings/[A-Za-z0-9_-]{43}/(status|redeem|cancel)"))))
        coroutineContext.ensureActive()
        val connection = URL(request.url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cookie", "")
            connection.setRequestProperty("Authorization", "Bearer ${requireNotNull(request.bearer)}")
            val bytes = (request.json ?: "{}").toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > 16_384) throw IOException("Pairing response exceeded size limit")
                    output.write(buffer, 0, size)
                }
            }
            coroutineContext.ensureActive()
            SpotifyResponse(status, output.toString("UTF-8"), connection.getHeaderField("Retry-After")?.toLongOrNull())
        } finally { connection.disconnect() }
    }
}
