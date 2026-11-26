package com.example.autohmi.music.pairing

import com.example.autohmi.music.SpotifyConfiguration
import com.example.autohmi.music.web.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyPairingControllerTest {
    private class Store : SpotifySecretStore {
        var value = SpotifySecrets()
        override suspend fun read() = value
        override suspend fun write(value: SpotifySecrets) { this.value = value }
    }
    private class SpotifyHttp : SpotifyTransport {
        val requests = mutableListOf<SpotifyRequest>()
        var failExchange = false
        override suspend fun execute(request: SpotifyRequest): SpotifyResponse {
            requests += request
            return when {
                request.url == SpotifyWebRepository.TOKEN_URL -> if (failExchange) SpotifyResponse(400) else SpotifyResponse(200,
                    """{"access_token":"fixture-access","refresh_token":"fixture-refresh","token_type":"Bearer","expires_in":3600}""")
                request.url.endsWith("/devices") -> SpotifyResponse(200, """{"devices":[]}""")
                else -> SpotifyResponse(204)
            }
        }
        override suspend fun artwork(url: String): ByteArray? = null
    }
    private class Broker(private val now: () -> Long) : PairingTransport {
        val requests = mutableListOf<SpotifyRequest>()
        var id = "Q".repeat(43)
        var state = ""
        var status = "waiting"
        var pollOffline = false
        var loseRedemption = false
        var wrongState = false
        override suspend fun execute(request: SpotifyRequest): SpotifyResponse {
            requests += request
            return when {
                request.url.endsWith("/v1/pairings") -> {
                    val input = JsonParser.parseString(request.json).asJsonObject
                    state = "$id.${input["stateNonce"].asString}"
                    SpotifyResponse(201, JsonObject().apply {
                        addProperty("id", id); addProperty("state", state); addProperty("qrUrl", "$ORIGIN/pair/$id")
                        addProperty("redirectUri", "$ORIGIN/oauth/spotify/callback"); addProperty("expiresAtMs", now() + 300_000)
                    }.toString())
                }
                request.url.endsWith("/status") -> {
                    if (pollOffline) throw IOException("fixture offline")
                    SpotifyResponse(200, """{"status":"$status"}""")
                }
                request.url.endsWith("/redeem") -> {
                    if (loseRedemption) throw IOException("fixture lost response")
                    SpotifyResponse(200, """{"state":"${if (wrongState) "mismatch" else state}","code":"fixture-code"}""")
                }
                else -> SpotifyResponse(200, """{"status":"cancelled"}""")
            }
        }
    }
    private inner class Fixture(scope: TestScope, configured: Boolean = true) {
        val store = Store(); val http = SpotifyHttp()
        val now = { BASE + scope.testScheduler.currentTime }
        val repo = SpotifyWebRepository(SpotifyConfiguration(CLIENT, CALLBACK), http, store, scope.backgroundScope, now)
        val broker = Broker(now)
        val controller = SpotifyPairingController(PairingConfiguration(if (configured) ORIGIN else ""), CLIENT, repo, broker, scope.backgroundScope, now)
        init { controller.setForeground(true) }
    }
    @Test fun successExchangesOnAndroidWithRetainedVerifierAndSeparateCallback() = runTest {
        val f = Fixture(this); f.controller.connect(); runCurrent()
        val pending = requireNotNull(f.store.value.pending)
        assertEquals(AuthorizationKind.QR, pending.kind)
        assertEquals(PairingPhase.WAITING, f.controller.state.value.phase)
        val creation = JsonParser.parseString(f.broker.requests.first().json).asJsonObject
        assertEquals(SpotifyPkce.challenge(pending.verifier), creation["challenge"].asString)
        val secret = requireNotNull(f.broker.requests.first().bearer)
        assertEquals(43, secret.length); assertNotEquals(pending.verifier, secret)
        val ui = f.controller.state.value.toString()
        for (privateValue in listOf(secret, pending.verifier, pending.state, "fixture-code", "fixture-access", "fixture-refresh")) assertFalse(ui.contains(privateValue))
        f.broker.status = "ready"; advanceTimeBy(3000); runCurrent()
        assertEquals(PairingPhase.CONNECTED, f.controller.state.value.phase)
        assertNull(f.controller.state.value.qrUrl); assertNull(f.store.value.pending)
        assertEquals(WebAuthStatus.AUTHORIZED, f.repo.web.value.auth)
        val exchange = f.http.requests.single()
        assertEquals(pending.verifier, exchange.form?.get("code_verifier"))
        assertEquals("$ORIGIN/oauth/spotify/callback", exchange.form?.get("redirect_uri"))
        assertFalse(exchange.form.orEmpty().containsKey("client_secret"))
        assertEquals("fixture-refresh", f.store.value.tokens?.refresh)
        assertFalse(f.controller.state.value.toString().contains("fixture-access"))
    }
    @Test fun cancelClearsPendingAndCallsAuthenticatedRelayCancellation() = runTest {
        val f = Fixture(this); f.controller.connect(); runCurrent(); f.controller.cancel(); runCurrent()
        assertEquals(PairingPhase.CANCELLED, f.controller.state.value.phase)
        assertNull(f.store.value.pending); assertNull(f.controller.state.value.qrUrl)
        assertTrue(f.broker.requests.any { it.url.endsWith("/cancel") && it.bearer == f.broker.requests.first().bearer })
        assertTrue(f.http.requests.isEmpty())
    }
    @Test fun expiresAfterFiveMinutesWithoutAnyCodeExchange() = runTest {
        val f = Fixture(this); f.controller.setForeground(false); f.controller.connect(); runCurrent()
        advanceTimeBy(300_001); runCurrent()
        assertEquals(PairingPhase.EXPIRED, f.controller.state.value.phase)
        assertNull(f.store.value.pending); assertNull(f.controller.state.value.qrUrl); assertTrue(f.http.requests.isEmpty())
    }
    @Test fun interruptedPollingRecoversButDoesNotLeakErrorDetails() = runTest {
        val f = Fixture(this); f.broker.pollOffline = true; f.controller.connect(); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals(PairingIssue.NETWORK, f.controller.state.value.issue)
        assertFalse(f.controller.state.value.toString().contains("fixture offline"))
        f.broker.pollOffline = false; f.broker.status = "ready"; advanceTimeBy(4000); runCurrent()
        assertEquals(PairingPhase.CONNECTED, f.controller.state.value.phase)
    }
    @Test fun lostOneUseDeliveryIsNeverRetriedAndNeedsANewQr() = runTest {
        val f = Fixture(this); f.broker.status = "ready"; f.broker.loseRedemption = true
        f.controller.connect(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(PairingIssue.REDEEMED, f.controller.state.value.issue)
        assertEquals(1, f.broker.requests.count { it.url.endsWith("/redeem") })
        assertTrue(f.http.requests.isEmpty()); assertNull(f.store.value.pending)
    }
    @Test fun mismatchedDeliveredStateNeverReachesSpotify() = runTest {
        val f = Fixture(this); f.broker.status = "ready"; f.broker.wrongState = true; f.controller.connect(); runCurrent()
        assertEquals(PairingIssue.BINDING, f.controller.state.value.issue)
        assertNull(f.store.value.pending); assertTrue(f.http.requests.isEmpty())
    }
    @Test fun failedExchangeDoesNotPersistTokensOrRetryConsumedCode() = runTest {
        val f = Fixture(this); f.broker.status = "ready"; f.http.failExchange = true; f.controller.connect(); runCurrent()
        assertEquals(PairingIssue.AUTH_FAILED, f.controller.state.value.issue)
        assertNull(f.store.value.pending); assertNull(f.store.value.tokens)
        advanceTimeBy(6000); runCurrent(); assertEquals(1, f.http.requests.size)
    }
    @Test fun newQrCancelsOldSessionBeforeCreatingTheReplacement() = runTest {
        val f = Fixture(this); f.controller.connect(); runCurrent()
        val first = requireNotNull(f.store.value.pending).verifier
        f.broker.id = "R".repeat(43); f.controller.connect(); runCurrent()
        assertNotEquals(first, f.store.value.pending?.verifier)
        assertTrue(f.controller.state.value.qrUrl.orEmpty().endsWith("R".repeat(43)))
        assertEquals(1, f.broker.requests.count { it.url.endsWith("/cancel") })
    }
    @Test fun backgroundPausesPollsAndReturningToForegroundResumes() = runTest {
        val f = Fixture(this); f.controller.setForeground(false); f.controller.connect(); runCurrent()
        advanceTimeBy(8000); runCurrent(); assertEquals(0, f.broker.requests.count { it.url.endsWith("/status") })
        f.controller.setForeground(true); runCurrent(); assertEquals(1, f.broker.requests.count { it.url.endsWith("/status") })
    }
    @Test fun unconfiguredRelayMakesNoNetworkOrVaultChanges() = runTest {
        val f = Fixture(this, false); f.controller.connect(); runCurrent()
        assertEquals(PairingPhase.UNCONFIGURED, f.controller.state.value.phase)
        assertTrue(f.broker.requests.isEmpty()); assertNull(f.store.value.pending)
    }
    @Test fun qrSessionUsesExistingRefreshAndLogoutWithoutRetainingPairingData() = runTest {
        val f = Fixture(this); f.broker.status = "ready"; f.controller.connect(); runCurrent()
        advanceTimeBy(3_601_000); runCurrent(); f.repo.refreshNow().join()
        assertTrue(f.http.requests.any { it.form?.get("grant_type") == "refresh_token" })
        f.repo.logout().join(); assertNull(f.store.value.tokens); assertNull(f.store.value.pending)
        assertEquals(WebAuthStatus.SIGNED_OUT, f.repo.web.value.auth)
    }
    @Test fun browserCallbackCannotConsumeQrAndQrCannotBeReplayed() = runTest {
        val f = Fixture(this); f.controller.connect(); runCurrent()
        val pending = requireNotNull(f.store.value.pending)
        f.repo.handleCallback("$CALLBACK?state=${pending.state}&code=fixture-code").join()
        assertTrue(f.http.requests.isEmpty()); assertNotNull(f.store.value.pending)
        assertNull(f.repo.completeQrAuthorization(pending.state, "fixture-code"))
        assertEquals(WebIssue.CALLBACK_INVALID, f.repo.completeQrAuthorization(pending.state, "fixture-code"))
        assertEquals(1, f.http.requests.size)
    }
    @Test fun processRestartAbandonsIncompleteQrButPreservesBrowserAuthorization() = runTest {
        val f = Fixture(this); f.controller.connect(); runCurrent()
        val restarted = SpotifyWebRepository(SpotifyConfiguration(CLIENT, CALLBACK), f.http, f.store, backgroundScope, f.now)
        runCurrent(); assertNull(f.store.value.pending); assertEquals(WebAuthStatus.SIGNED_OUT, restarted.web.value.auth)
        f.controller.cancel(); runCurrent()
        assertNotNull(restarted.prepareAuthorization())
        val browser = requireNotNull(f.store.value.pending)
        assertEquals(AuthorizationKind.BROWSER, browser.kind)
        restarted.handleCallback("$CALLBACK?state=${browser.state}&code=fixture-browser-code").join()
        assertEquals(WebAuthStatus.AUTHORIZED, restarted.web.value.auth)
        assertEquals(CALLBACK, f.http.requests.single().form?.get("redirect_uri"))
    }
    companion object {
        private const val BASE = 1_800_000_000_000L
        private const val CLIENT = "0123456789abcdef0123456789abcdef"
        private const val ORIGIN = "https://relay.test.invalid"
        private const val CALLBACK = "https://callback.test.invalid/spotify/callback.html"
    }
}
