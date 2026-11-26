package com.example.autohmi.music.web

import org.junit.Assert.*
import org.junit.Test

class SpotifyPkceTest {
    private val redirect = "https://callback.test.invalid/spotify/callback.html"
    @Test fun s256MatchesTheRfc7636ExampleAndNoncesAreUnique() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SpotifyPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val attempts = List(100) { SpotifyPkce.pending(1000) }
        assertEquals(100, attempts.map { it.state }.toSet().size)
        assertTrue(attempts.all { it.state.length >= 43 && it.verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")) })
    }
    @Test fun strictOriginStateDuplicatesAndAmbiguousResponsesCannotYieldACode() {
        val pending = PendingAuthorization("fixture-state", "fixture-verifier", 1000)
        listOf(
            "$redirect?code=fixture", "$redirect?code=fixture&state=wrong",
            "$redirect?code=fixture&state=fixture-state&state=fixture-state",
            "$redirect?code=fixture&code=another&state=fixture-state",
            "$redirect?code=fixture&error=denied&state=fixture-state",
            "$redirect?code=fixture&state=%ZZ", "$redirect?code=fixture&state=fixture-state#fragment",
            "$redirect?code=&state=fixture-state", redirect.replace("https", "http") + "?code=fixture&state=fixture-state",
            redirect.replace("callback.test.invalid", "other.test.invalid") + "?code=fixture&state=fixture-state"
        ).forEach { assertNull(SpotifyPkce.callback(it, redirect, pending, 2000).code) }
        assertNotNull(SpotifyPkce.callback("$redirect?code=fixture&state=fixture-state", redirect, pending, 2000).code)
    }
    @Test fun expiryAndDenialConsumeOnlyPendingAuthorizationAndSecretsStayRedacted() {
        val pending = PendingAuthorization("fixture-state", "fixture-verifier", 1000)
        assertEquals(WebIssue.AUTH_EXPIRED, SpotifyPkce.callback("$redirect?code=fixture&state=fixture-state", redirect, pending, 1000 + SpotifyPkce.MAX_AGE_MS + 1).issue)
        val denied = SpotifyPkce.callback("$redirect?error=access_denied&state=fixture-state", redirect, pending, 2000)
        assertTrue(denied.consume)
        assertEquals(WebIssue.AUTH_CANCELLED, denied.issue)
        assertFalse(pending.toString().contains(pending.verifier))
        assertFalse(OAuthTokens("fixture-access", "fixture-refresh", 0).toString().contains("fixture-access"))
    }
    @Test fun authorizationUsesOnlyPlaybackScopesAndNeverContainsTheVerifier() {
        val pending = SpotifyPkce.pending(1000)
        val url = SpotifyPkce.authorizationUrl("fixture-client", redirect, pending)
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertFalse(url.contains(pending.verifier))
        assertFalse(url.contains("secret"))
        assertFalse(url.contains("user-read-email"))
    }
    @Test fun providerConfigurationErrorsAreNotReportedAsUserCancellation() {
        val pending = PendingAuthorization("fixture-state", "fixture-verifier", 1000)
        val result = SpotifyPkce.callback("$redirect?error=invalid_scope&state=fixture-state", redirect, pending, 2000)
        assertEquals(WebIssue.AUTH_FAILED, result.issue)
        assertTrue(result.consume)
    }
}
