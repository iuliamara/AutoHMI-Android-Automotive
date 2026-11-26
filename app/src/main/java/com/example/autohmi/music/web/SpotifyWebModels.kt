package com.example.autohmi.music.web

import com.example.autohmi.music.MusicPlayerState

enum class WebAuthStatus { RESTORING, SIGNED_OUT, AUTHORIZING, EXCHANGING, AUTHORIZED }
enum class WebIssue {
    CONFIGURATION, BROWSER_UNAVAILABLE, AUTH_CANCELLED, CALLBACK_INVALID, AUTH_EXPIRED,
    AUTH_FAILED, SESSION_EXPIRED, STORAGE, NETWORK, ACCESS_DENIED, NO_DEVICE,
    DEVICE_UNAVAILABLE, RATE_LIMITED, SERVICE, PLAYBACK_RESTRICTED
}

data class ConnectDevice(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val restricted: Boolean
)

data class SpotifyWebState(
    val auth: WebAuthStatus = WebAuthStatus.RESTORING,
    val issue: WebIssue? = null,
    val devices: List<ConnectDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val playbackDevice: ConnectDevice? = null,
    val busy: Boolean = false,
    val retryAtMs: Long = 0,
    val lastUpdatedMs: Long = 0,
    val stale: Boolean = false
)

/** These objects must never render their sensitive fields in exceptions/logs. */
class OAuthTokens(val access: String, val refresh: String, val expiresAtMs: Long) {
    override fun toString() = "OAuthTokens([redacted])"
}
enum class AuthorizationKind { BROWSER, QR }
class PendingAuthorization(val state: String, val verifier: String, val createdAtMs: Long,
    val kind: AuthorizationKind = AuthorizationKind.BROWSER, val redirectUri: String? = null) {
    override fun toString() = "PendingAuthorization([redacted])"
}
class QrAuthorizationMaterial(val stateNonce: String, val challenge: String) {
    override fun toString() = "QrAuthorizationMaterial([redacted])"
}
class SpotifySecrets(val tokens: OAuthTokens? = null, val pending: PendingAuthorization? = null) {
    override fun toString() = "SpotifySecrets([redacted])"
}
interface SpotifySecretStore {
    suspend fun read(): SpotifySecrets
    suspend fun write(value: SpotifySecrets)
}

class SpotifyRequest(
    val method: String,
    val url: String,
    val bearer: String? = null,
    val form: Map<String, String>? = null,
    val json: String? = null
) {
    override fun toString() = "SpotifyRequest([redacted])"
}
class SpotifyResponse(val status: Int, val body: String = "", val retryAfterSeconds: Long? = null) {
    override fun toString() = "SpotifyResponse(status=$status, body=[redacted])"
}
interface SpotifyTransport {
    suspend fun execute(request: SpotifyRequest): SpotifyResponse
    suspend fun artwork(url: String): ByteArray?
}
data class PlaybackSnapshot(val player: MusicPlayerState, val device: ConnectDevice?)
