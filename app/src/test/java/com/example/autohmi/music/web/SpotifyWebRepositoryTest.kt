package com.example.autohmi.music.web

import com.example.autohmi.music.SpotifyConfiguration
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyWebRepositoryTest {
    private val config = SpotifyConfiguration("0123456789abcdef0123456789abcdef", "https://callback.test.invalid/spotify/callback.html")
    private class Store(var value: SpotifySecrets = SpotifySecrets()) : SpotifySecretStore {
        override suspend fun read() = value
        override suspend fun write(value: SpotifySecrets) { this.value = value }
    }
    private class Http : SpotifyTransport {
        val requests = mutableListOf<SpotifyRequest>()
        var handler: suspend (SpotifyRequest) -> SpotifyResponse = { request ->
            when {
                request.url.endsWith("/devices") -> SpotifyResponse(200, DEVICES)
                request.url.endsWith("/me/player") -> SpotifyResponse(200, playback())
                request.url == SpotifyWebRepository.TOKEN_URL -> SpotifyResponse(200, TOKEN)
                else -> SpotifyResponse(204)
            }
        }
        override suspend fun execute(request: SpotifyRequest): SpotifyResponse { requests += request; return handler(request) }
        override suspend fun artwork(url: String) = byteArrayOf(1, 2, 3)
    }
    private fun TestScope.repository(http: Http, store: Store) = SpotifyWebRepository(config, http, store, backgroundScope) { BASE + testScheduler.currentTime }
    private fun signedIn() = Store(SpotifySecrets(tokens = OAuthTokens("fixture-access", "fixture-refresh", BASE + 3_600_000)))

    @Test fun exchangeValidatesStateAndSingleUseBeforePersistingSession() = runTest {
        val http = Http(); val store = Store(); val repo = repository(http, store)
        repo.prepareAuthorization()
        val pending = requireNotNull(store.value.pending)
        repo.handleCallback("${config.redirectUri}?state=wrong&code=synthetic-code").join()
        assertTrue(http.requests.isEmpty())
        assertNotNull(store.value.pending)
        val callback = "${config.redirectUri}?state=${pending.state}&code=synthetic-code"
        repo.handleCallback(callback).join()
        assertEquals(WebAuthStatus.AUTHORIZED, repo.web.value.auth)
        assertNull(store.value.pending)
        val request = http.requests.single()
        assertEquals("authorization_code", request.form?.get("grant_type"))
        assertEquals(pending.verifier, request.form?.get("code_verifier"))
        assertNull(request.bearer)
        assertFalse(request.form.orEmpty().containsKey("client_secret"))
        repo.handleCallback(callback).join()
        assertEquals(1, http.requests.size)
    }

    @Test fun pendingSurvivesProcessRecreationAndCancelPreventsAnOldCallback() = runTest {
        val http = Http(); val store = Store()
        val first = repository(http, store)
        first.prepareAuthorization()
        val pending = requireNotNull(store.value.pending)
        val recreated = repository(http, store)
        recreated.cancelAuthorization().join()
        recreated.handleCallback("${config.redirectUri}?state=${pending.state}&code=synthetic-code").join()
        assertTrue(http.requests.isEmpty())
        assertEquals(WebAuthStatus.SIGNED_OUT, recreated.web.value.auth)
    }

    @Test fun authorizedWithNoDeviceIsNotAReadyPlayer() = runTest {
        val http = Http().apply { handler = { if (it.url.endsWith("/devices")) SpotifyResponse(200, "{\"devices\":[]}") else SpotifyResponse(204) } }
        val repo = repository(http, signedIn())
        repo.refreshNow().join()
        assertEquals(WebAuthStatus.AUTHORIZED, repo.web.value.auth)
        assertEquals(WebIssue.NO_DEVICE, repo.web.value.issue)
        assertTrue(repo.web.value.devices.isEmpty())
        assertFalse(repo.state.value.canPlay)
    }

    @Test fun expiredAccessRefreshesAndPreservesAnUnrotatedRefreshToken() = runTest {
        val http = Http().apply { handler = { request -> when {
            request.url == SpotifyWebRepository.TOKEN_URL -> SpotifyResponse(200, """{"access_token":"fixture-new-access","token_type":"Bearer","expires_in":3600}""")
            request.url.endsWith("/devices") -> SpotifyResponse(200, DEVICES)
            else -> SpotifyResponse(200, playback())
        } } }
        val store = Store(SpotifySecrets(tokens = OAuthTokens("fixture-old-access", "fixture-retained-refresh", BASE + 1000)))
        val repo = repository(http, store)
        repo.refreshNow().join()
        assertEquals("fixture-new-access", store.value.tokens?.access)
        assertEquals("fixture-retained-refresh", store.value.tokens?.refresh)
        assertEquals(1, http.requests.count { it.url == SpotifyWebRepository.TOKEN_URL })
    }

    @Test fun a401RefreshesOnceButRevocationSignsOutWithoutRetryLoop() = runTest {
        val http = Http(); var refreshed = false
        http.handler = { request -> when {
            request.url == SpotifyWebRepository.TOKEN_URL -> { refreshed = true; SpotifyResponse(400) }
            else -> SpotifyResponse(401)
        } }
        val store = signedIn(); val repo = repository(http, store)
        repo.refreshNow().join()
        assertTrue(refreshed)
        assertNull(store.value.tokens)
        assertEquals(WebAuthStatus.SIGNED_OUT, repo.web.value.auth)
        assertEquals(WebIssue.SESSION_EXPIRED, repo.web.value.issue)
        assertEquals(2, http.requests.size)
    }

    @Test fun rateLimitBlocksAllCallsUntilRetryAfterWithoutAutomaticCommandReplay() = runTest {
        val http = Http().apply { handler = { SpotifyResponse(429, retryAfterSeconds = 10) } }
        val repo = repository(http, signedIn())
        repo.refreshNow().join()
        repo.refreshNow().join()
        assertEquals(1, http.requests.size)
        assertEquals(WebIssue.RATE_LIMITED, repo.web.value.issue)
        advanceTimeBy(9_000); repo.refreshNow().join()
        assertEquals(1, http.requests.size)
        advanceTimeBy(1_001); repo.refreshNow().join()
        assertEquals(2, http.requests.size)
    }

    @Test fun deviceSelectionTransfersWithoutAutoplayAndCommandsUseSelectedWindowsId() = runTest {
        val http = Http(); var chosen = "other"
        http.handler = { request -> when {
            request.method == "PUT" && request.url.endsWith("/me/player") -> { chosen = "windows"; SpotifyResponse(204) }
            request.url.endsWith("/devices") -> SpotifyResponse(200, DEVICES)
            request.url.endsWith("/me/player") -> SpotifyResponse(200, playback(device = chosen, playing = false))
            else -> SpotifyResponse(204)
        } }
        val repo = repository(http, signedIn())
        repo.refreshNow().join()
        repo.selectDevice("windows").join()
        val transfer = http.requests.single { it.method == "PUT" && it.url.endsWith("/me/player") }
        assertTrue(transfer.json.orEmpty().contains("\"play\":false"))
        assertEquals("windows", repo.web.value.selectedDeviceId)
        assertTrue(repo.state.value.canPlay)
        repo.play(); runCurrent()
        assertTrue(http.requests.any { it.url.endsWith("/play?device_id=windows") })
        assertFalse(repo.state.value.isPlaying) // An HTTP acknowledgment is not observed playback.
    }

    @Test fun allControlsUseObservedRestrictionsAndSeekClampsToDuration() = runTest {
        val http = Http(); val repo = repository(http, signedIn())
        repo.refreshNow().join()
        repo.pause(); runCurrent(); repo.next(); runCurrent(); repo.previous(); runCurrent(); repo.seekTo(Long.MAX_VALUE); runCurrent()
        assertTrue(http.requests.any { it.url.endsWith("/pause?device_id=windows") })
        assertTrue(http.requests.any { it.url.endsWith("/next?device_id=windows") })
        assertTrue(http.requests.any { it.url.endsWith("/previous?device_id=windows") })
        assertTrue(http.requests.any { it.url.contains("/seek?device_id=windows&position_ms=60000") })
        assertNotNull(repo.state.value.artworkBytes)
    }

    @Test fun pollingEstimatesProgressThenStopsOnBackgroundWithoutCatchingUp() = runTest {
        val http = Http(); val repo = repository(http, signedIn())
        repo.setForeground(true); runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertTrue(repo.state.value.positionMs >= 3_000)
        repo.setForeground(false); runCurrent()
        val count = http.requests.size
        advanceTimeBy(30_000); runCurrent()
        assertEquals(count, http.requests.size)
    }

    @Test fun logoutClearsStoredSessionAndLateArtworkCannotRestoreIt() = runTest {
        val http = Http(); val store = signedIn(); val repo = repository(http, store)
        repo.refreshNow().join()
        repo.logout().join()
        assertNull(store.value.tokens)
        assertNull(store.value.pending)
        assertNull(repo.state.value.track)
        assertEquals(WebAuthStatus.SIGNED_OUT, repo.web.value.auth)
        repo.refreshNow().join()
        assertEquals(WebAuthStatus.SIGNED_OUT, repo.web.value.auth)
    }

    @Test fun unavailableDeviceAndPermissionErrorsHaveDistinctSafeUiStates() = runTest {
        for ((status, issue) in listOf(403 to WebIssue.ACCESS_DENIED, 404 to WebIssue.DEVICE_UNAVAILABLE, 503 to WebIssue.SERVICE)) {
            val http = Http().apply { handler = { SpotifyResponse(status, "untrusted server details") } }
            val repo = repository(http, signedIn())
            repo.refreshNow().join()
            assertEquals(issue, repo.web.value.issue)
            assertFalse(repo.state.value.canNext)
        }
    }

    @Test fun networkFailureDoesNotLogOutAndStorageFailureNeverOpensBrowser() = runTest {
        val http = Http().apply { handler = { throw IOException("synthetic failure") } }
        val repo = repository(http, signedIn())
        repo.refreshNow().join()
        assertEquals(WebIssue.NETWORK, repo.web.value.issue)
        assertEquals(WebAuthStatus.AUTHORIZED, repo.web.value.auth)
        val badStore = object : SpotifySecretStore {
            override suspend fun read() = SpotifySecrets()
            override suspend fun write(value: SpotifySecrets) { throw IOException("synthetic storage failure") }
        }
        val blocked = SpotifyWebRepository(config, http, badStore, backgroundScope) { BASE }
        assertNull(blocked.prepareAuthorization())
        assertEquals(WebIssue.STORAGE, blocked.web.value.issue)
    }

    @Test fun parsingRestrictionsSupportsBothSpotifyActionShapes() {
        val nested = playback().replace("\"actions\":{}", "\"actions\":{\"disallows\":{\"skipping_next\":true,\"seeking\":true}}")
        assertFalse(SpotifyJson.playback(nested).player.canNext)
        assertFalse(SpotifyJson.playback(nested).player.canSeek)
        val direct = playback().replace("\"actions\":{}", "\"actions\":{\"skipping_next\":true}")
        assertFalse(SpotifyJson.playback(direct).player.canNext)
    }

    @Test fun aRestoredPendingAttemptCanCompleteWithoutTheOriginalRepository() = runTest {
        val store = Store(); val http = Http()
        repository(http, store).prepareAuthorization()
        val pending = requireNotNull(store.value.pending)
        val restored = repository(http, store)
        restored.handleCallback("${config.redirectUri}?state=${pending.state}&code=synthetic-code").join()
        assertEquals(WebAuthStatus.AUTHORIZED, restored.web.value.auth)
        assertNotNull(store.value.tokens)
        assertNull(store.value.pending)
    }

    @Test fun unauthorizedRequestRefreshesOnceAndUsesRotatedCredentialsForTheRetry() = runTest {
        val store = signedIn(); val http = Http()
        http.handler = { request -> when {
            request.url == SpotifyWebRepository.TOKEN_URL -> SpotifyResponse(200, """{"access_token":"fixture-new-access","refresh_token":"fixture-rotated-refresh","token_type":"Bearer","expires_in":3600}""")
            request.bearer == "fixture-access" -> SpotifyResponse(401)
            request.url.endsWith("/devices") -> SpotifyResponse(200, DEVICES)
            else -> SpotifyResponse(200, playback())
        } }
        val repo = repository(http, store)
        repo.refreshNow().join()
        assertEquals(1, http.requests.count { it.url == SpotifyWebRepository.TOKEN_URL })
        assertEquals("fixture-rotated-refresh", store.value.tokens?.refresh)
        assertEquals(WebAuthStatus.AUTHORIZED, repo.web.value.auth)
        assertNotNull(repo.state.value.track)
    }

    @Test fun inactivePlaybackDisablesCommandsWithoutDiscardingAuthorization() = runTest {
        val http = Http().apply { handler = { if (it.url.endsWith("/devices")) SpotifyResponse(200, DEVICES)
            else SpotifyResponse(200, playback().replace("\"is_active\":true", "\"is_active\":false")) } }
        val repo = repository(http, signedIn())
        repo.refreshNow().join()
        assertEquals(WebIssue.DEVICE_UNAVAILABLE, repo.web.value.issue)
        assertEquals(WebAuthStatus.AUTHORIZED, repo.web.value.auth)
        assertFalse(repo.state.value.canPause)
        assertFalse(repo.state.value.canNext)
    }

    @Test fun aFailedSkipIsNotReplayedOrImmediatelyHiddenByARefresh() = runTest {
        val http = Http(); val original = http.handler
        http.handler = { if (it.url.contains("/next?")) SpotifyResponse(404) else original(it) }
        val repo = repository(http, signedIn())
        repo.refreshNow().join()
        repo.next(); runCurrent()
        assertEquals(1, http.requests.count { it.url.contains("/next?") })
        assertEquals(WebIssue.DEVICE_UNAVAILABLE, repo.web.value.issue)
        assertTrue(repo.web.value.stale)
    }

    @Test fun repeatedRefreshesShareOneInFlightRequest() = runTest {
        val gate = CompletableDeferred<Unit>(); val http = Http(); val original = http.handler
        http.handler = { if (it.url.endsWith("/devices")) gate.await(); original(it) }
        val repo = repository(http, signedIn())
        val first = repo.refreshNow(); runCurrent()
        val second = repo.refreshNow(); runCurrent()
        assertTrue(first === second)
        assertEquals(1, http.requests.size)
        gate.complete(Unit); first.join()
        assertEquals(2, http.requests.size)
    }

    @Test fun logoutWinsOverAnInFlightSnapshotAndLeavesNoStoredSession() = runTest {
        val gate = CompletableDeferred<Unit>(); val http = Http(); val original = http.handler
        http.handler = { if (it.url.endsWith("/devices")) gate.await(); original(it) }
        val store = signedIn(); val repo = repository(http, store)
        val refresh = repo.refreshNow(); runCurrent()
        val logout = repo.logout(); runCurrent()
        gate.complete(Unit); refresh.join(); logout.join()
        assertNull(store.value.tokens)
        assertNull(repo.state.value.track)
        assertEquals(WebAuthStatus.SIGNED_OUT, repo.web.value.auth)
    }

    companion object {
        private const val BASE = 10_000_000L
        private const val TOKEN = """{"access_token":"fixture-access","refresh_token":"fixture-refresh","token_type":"Bearer","expires_in":3600,"scope":"user-read-playback-state user-modify-playback-state"}"""
        private const val DEVICES = """{"devices":[{"id":"windows","name":"Windows fixture","type":"Computer","is_active":true,"is_restricted":false},{"id":"other","name":"Other fixture","type":"Speaker","is_active":false,"is_restricted":false}]}"""
        private fun playback(device: String = "windows", playing: Boolean = true) = """{"device":{"id":"$device","name":"Windows fixture","type":"Computer","is_active":true,"is_restricted":false},"item":{"uri":"spotify:track:fixture","name":"Fixture song","artists":[{"name":"Fixture artist"}],"album":{"images":[{"url":"https://i.scdn.co/image/fixture"}]},"duration_ms":60000},"progress_ms":1000,"is_playing":$playing,"actions":{}}"""
    }
}
