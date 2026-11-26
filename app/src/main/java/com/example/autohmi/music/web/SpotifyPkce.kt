package com.example.autohmi.music.web

import com.example.autohmi.music.SpotifyCallbackTarget
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object SpotifyPkce {
    const val SCOPES = "user-read-playback-state user-modify-playback-state"
    const val MAX_AGE_MS = 10 * 60 * 1000L
    const val QR_MAX_AGE_MS = 5 * 60 * 1000L
    private val random = SecureRandom()
    private fun nonce() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun pending(nowMs: Long) = PendingAuthorization(nonce(), nonce(), nowMs)
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))
    fun encode(value: String): String = URLEncoder.encode(value, UTF_8.name())
    fun authorizationUrl(clientId: String, redirect: String, pending: PendingAuthorization): String {
        require(pending.kind == AuthorizationKind.BROWSER)
        val parameters = linkedMapOf(
            "client_id" to clientId, "response_type" to "code", "redirect_uri" to redirect,
            "scope" to SCOPES, "state" to pending.state, "code_challenge_method" to "S256",
            "code_challenge" to challenge(pending.verifier), "show_dialog" to "true"
        )
        return "https://accounts.spotify.com/authorize?" + parameters.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
    }

    class Callback(val code: String? = null, val issue: WebIssue? = null, val consume: Boolean = false) {
        override fun toString() = "Callback([redacted])"
    }
    fun callback(raw: String?, redirect: String, pending: PendingAuthorization?, nowMs: Long): Callback {
        if (raw == null || raw.length > 8192 || pending == null || pending.kind != AuthorizationKind.BROWSER) return Callback(issue = WebIssue.CALLBACK_INVALID)
        if (nowMs - pending.createdAtMs !in 0..MAX_AGE_MS) return Callback(issue = WebIssue.AUTH_EXPIRED, consume = true)
        if (SpotifyCallbackTarget.parse(redirect)?.accepts(raw) != true) return Callback(issue = WebIssue.CALLBACK_INVALID)
        val query = runCatching { URI(raw).rawQuery }.getOrNull() ?: return Callback(issue = WebIssue.CALLBACK_INVALID)
        val values = linkedMapOf<String, String>()
        try {
            for (part in query.split('&')) {
                val pair = part.split('=', limit = 2)
                val key = URLDecoder.decode(pair[0], UTF_8.name())
                if (values.containsKey(key) || pair.size != 2) return Callback(issue = WebIssue.CALLBACK_INVALID)
                // Ignore descriptive server text. Only protocol fields are needed.
                values[key] = if (key in setOf("state", "code", "error")) URLDecoder.decode(pair[1], UTF_8.name()) else ""
            }
        } catch (_: Exception) { return Callback(issue = WebIssue.CALLBACK_INVALID) }
        val state = values["state"] ?: return Callback(issue = WebIssue.CALLBACK_INVALID)
        if (!MessageDigest.isEqual(state.toByteArray(UTF_8), pending.state.toByteArray(UTF_8))) return Callback(issue = WebIssue.CALLBACK_INVALID)
        if (values.containsKey("code") && values.containsKey("error")) return Callback(issue = WebIssue.CALLBACK_INVALID)
        if (values.containsKey("error")) return Callback(issue = if (values["error"] == "access_denied") WebIssue.AUTH_CANCELLED else WebIssue.AUTH_FAILED, consume = true)
        val code = values["code"]?.takeIf { it.isNotBlank() && it.length <= 2048 }
            ?: return Callback(issue = WebIssue.CALLBACK_INVALID)
        return Callback(code = code, consume = true)
    }
}
