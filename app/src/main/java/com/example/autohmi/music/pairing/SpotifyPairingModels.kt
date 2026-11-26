package com.example.autohmi.music.pairing

import com.example.autohmi.music.web.SpotifyRequest
import com.example.autohmi.music.web.SpotifyResponse
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

class PairingConfiguration(value: String) {
    val origin: String? = runCatching {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
        require(uri.rawPath.orEmpty().isEmpty() && uri.host.matches(Regex("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")))
        require(uri.host != "localhost" && !uri.host.startsWith("127."))
        "https://" + uri.host.lowercase()
    }.getOrNull()
    val callbackUri get() = origin?.plus("/oauth/spotify/callback")
    val isConfigured get() = origin != null
}

enum class PairingPhase { IDLE, UNCONFIGURED, GENERATING, WAITING, CONNECTING, CONNECTED, EXPIRED, CANCELLED, ERROR }
enum class PairingIssue { CONFIGURATION, NETWORK, SERVICE, RATE_LIMITED, DECLINED, BINDING, REDEEMED, AUTH_FAILED, STORAGE, BUSY }
data class SpotifyPairingState(
    val phase: PairingPhase = PairingPhase.IDLE,
    val qrUrl: String? = null,
    val remainingSeconds: Int = 0,
    val issue: PairingIssue? = null
)
interface PairingTransport { suspend fun execute(request: SpotifyRequest): SpotifyResponse }
class UnconfiguredPairingTransport : PairingTransport {
    override suspend fun execute(request: SpotifyRequest): SpotifyResponse = error("QR pairing is not configured")
}
internal fun pairingNonce(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

internal class PairingSession(val secret: String, val nonce: String, val clientExpiresAt: Long) {
    var id: String? = null
    var state: String = nonce
    var expiresAt: Long = clientExpiresAt
    override fun toString() = "PairingSession([redacted])"
}
