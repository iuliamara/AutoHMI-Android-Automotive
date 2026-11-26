package com.example.autohmi.music.pairing

import com.example.autohmi.music.web.SpotifyPkce
import com.example.autohmi.music.web.SpotifyRequest
import com.example.autohmi.music.web.SpotifyResponse
import com.example.autohmi.music.web.SpotifyWebRepository
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** ViewModel-owned QR transaction; its UI StateFlow contains only a public QR URL and sanitized state. */
class SpotifyPairingController(
    private val configuration: PairingConfiguration,
    private val clientId: String,
    private val spotify: SpotifyWebRepository,
    private val transport: PairingTransport,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow(SpotifyPairingState())
    val state = _state.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private var job: Job? = null
    private var generation = 0L
    private class Failure(val issue: PairingIssue) : Exception("Pairing failed")
    fun setForeground(value: Boolean) { foreground.value = value }
    fun connect() {
        val previous = job
        previous?.cancel()
        val ticket = ++generation
        _state.value = SpotifyPairingState(if (configuration.isConfigured) PairingPhase.GENERATING else PairingPhase.UNCONFIGURED)
        if (!configuration.isConfigured) return
        job = scope.launch { previous?.join(); if (ticket == generation) pair(ticket) }
    }
    fun cancel() {
        generation++
        job?.cancel() // Keep the reference so a subsequent Connect waits for cleanup.
        _state.value = SpotifyPairingState(PairingPhase.CANCELLED)
    }
    suspend fun cancelAndJoin() {
        val previous = job
        cancel()
        previous?.join()
    }
    fun close() { cancel() }
    private fun publish(ticket: Long, value: SpotifyPairingState) { if (ticket == generation) _state.value = value }
    private suspend fun request(session: PairingSession, path: String, body: String? = null): SpotifyResponse {
        val response = transport.execute(SpotifyRequest("POST", configuration.origin + path, bearer = session.secret, json = body))
        currentCoroutineContext().ensureActive()
        return response
    }
    private suspend fun pair(ticket: Long) {
        var session: PairingSession? = null
        var completed = false
        try {
            val material = spotify.prepareQrAuthorization(requireNotNull(configuration.callbackUri)) ?: throw Failure(PairingIssue.BUSY)
            session = PairingSession(pairingNonce(), material.stateNonce, now() + SpotifyPkce.QR_MAX_AGE_MS)
            val input = JsonObject().apply {
                addProperty("clientId", clientId); addProperty("challenge", material.challenge); addProperty("stateNonce", material.stateNonce)
            }
            val created = request(session, "/v1/pairings", input.toString())
            if (created.status == 429) throw Failure(PairingIssue.RATE_LIMITED)
            if (created.status != 201) throw Failure(if (created.status == 400 || created.status == 503) PairingIssue.CONFIGURATION else PairingIssue.SERVICE)
            val response = JsonParser.parseString(created.body).asJsonObject
            val id = response.get("id").asString
            require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
            session.id = id
            val expected = "$id.${material.stateNonce}"
            if (response.get("state").asString != expected || response.get("qrUrl").asString != "${configuration.origin}/pair/$id" ||
                response.get("redirectUri").asString != configuration.callbackUri) throw Failure(PairingIssue.BINDING)
            if (!spotify.bindQrAuthorization(material.stateNonce, expected)) throw Failure(PairingIssue.BINDING)
            session.state = expected
            session.expiresAt = minOf(response.get("expiresAtMs").asLong, session.clientExpiresAt)
            if (session.expiresAt <= now()) { publish(ticket, SpotifyPairingState(PairingPhase.EXPIRED)); return }
            val qrUrl = "${configuration.origin}/pair/$id"
            var nextPoll = now()
            var issue: PairingIssue? = null
            while (now() < session.expiresAt) {
                if (!foreground.value) {
                    if (withTimeoutOrNull((session.expiresAt - now()).coerceAtLeast(1)) { foreground.first { it } } == null) break
                }
                publish(ticket, SpotifyPairingState(PairingPhase.WAITING, qrUrl, ((session.expiresAt - now() + 999) / 1000).toInt().coerceAtLeast(0), issue))
                if (now() >= nextPoll) {
                    try {
                        val status = request(session, "/v1/pairings/$id/status")
                        nextPoll = now() + if (status.status == 429) (status.retryAfterSeconds ?: 30).coerceIn(1, 300) * 1000 else 3000
                        if (status.status == 401 || status.status == 403) throw Failure(PairingIssue.BINDING)
                        if (status.status == 429 || status.status >= 500) {
                            issue = if (status.status == 429) PairingIssue.RATE_LIMITED else PairingIssue.SERVICE
                        } else {
                            val value = JsonParser.parseString(status.body).asJsonObject.get("status").asString
                            when (value) {
                                "expired" -> break
                                "cancelled" -> { publish(ticket, SpotifyPairingState(PairingPhase.CANCELLED)); return }
                                "redeemed" -> throw Failure(PairingIssue.REDEEMED)
                                "denied" -> throw Failure(PairingIssue.DECLINED)
                                "ready" -> {
                                    publish(ticket, SpotifyPairingState(PairingPhase.CONNECTING))
                                    // Never automatically retry this one-use delivery or Spotify token exchange.
                                    val redeemed = request(session, "/v1/pairings/$id/redeem")
                                    if (redeemed.status != 200) throw Failure(PairingIssue.REDEEMED)
                                    val payload = JsonParser.parseString(redeemed.body).asJsonObject
                                    if (payload.get("state").asString != session.state) throw Failure(PairingIssue.BINDING)
                                    val code = payload.get("code").asString
                                    if (spotify.completeQrAuthorization(session.state, code) != null) throw Failure(PairingIssue.AUTH_FAILED)
                                    completed = true
                                    publish(ticket, SpotifyPairingState(PairingPhase.CONNECTED))
                                    return
                                }
                                "waiting" -> issue = null
                                else -> throw Failure(PairingIssue.SERVICE)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Failure) { throw failure }
                    catch (_: java.io.IOException) {
                        if (_state.value.phase == PairingPhase.CONNECTING) throw Failure(PairingIssue.REDEEMED)
                        issue = PairingIssue.NETWORK; nextPoll = now() + 5000
                    }
                }
                delay(1000)
            }
            publish(ticket, SpotifyPairingState(PairingPhase.EXPIRED))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Failure) { publish(ticket, SpotifyPairingState(PairingPhase.ERROR, issue = failure.issue)) }
        catch (_: java.io.IOException) { publish(ticket, SpotifyPairingState(PairingPhase.ERROR, issue = PairingIssue.NETWORK)) }
        catch (_: Exception) { publish(ticket, SpotifyPairingState(PairingPhase.ERROR, issue = PairingIssue.SERVICE)) }
        finally {
            session?.let { current -> withContext(NonCancellable) {
                if (!completed) {
                    runCatching { spotify.cancelQrAuthorization(current.state) }
                    // Cancellation can arrive between binding the composite state and assigning it locally.
                    current.id?.let { runCatching { spotify.cancelQrAuthorization("$it.${current.nonce}") } }
                    withTimeoutOrNull(2500) { runCatching { current.id?.let { request(current, "/v1/pairings/$it/cancel") } } }
                }
            } }
        }
    }
}
