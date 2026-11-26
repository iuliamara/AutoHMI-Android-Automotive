package com.example.autohmi.music

import org.junit.Assert.*
import org.junit.Test

class SpotifyCallbackTargetTest {
    private val callback = "https://callback.test.invalid/spotify/callback.html"

    @Test
    fun canonicalHttpsHostAndExactPathAreParsedWithoutAssumingOwnership() {
        assertEquals(SpotifyCallbackTarget("callback.test.invalid", "/spotify/callback.html"), SpotifyCallbackTarget.parse(callback))
        assertNotNull(SpotifyCallbackTarget.parse(callback.replace("callback.test.invalid", "CALLBACK.TEST.INVALID")))
    }

    @Test
    fun missingInsecureAmbiguousOrNonCanonicalConfigurationIsRejected() {
        listOf(
            "", "http://callback.test.invalid/spotify/callback.html", "https://localhost/callback",
            "https://callback.test.invalid", "https://callback.test.invalid/",
            "https://user@callback.test.invalid/spotify/callback.html",
            "$callback?code=unused", "$callback#unused",
            "https://callback.test.invalid:443/spotify/callback.html",
            "https://callback.test.invalid/spotify/../callback.html",
            "https://callback.test.invalid/spotify/%63allback.html"
        ).forEach { assertNull(it, SpotifyCallbackTarget.parse(it)) }
    }

    @Test
    fun matchingReturnAcceptsButDoesNotInterpretQueryParameters() {
        val target = requireNotNull(SpotifyCallbackTarget.parse(callback))
        assertTrue(target.accepts(callback))
        assertTrue(target.accepts("$callback?code=unused&state=unused"))
        assertTrue(target.accepts("$callback?error=access_denied"))
    }

    @Test
    fun foreignOriginsPortsFragmentsAndPathConfusionAreRejected() {
        val target = requireNotNull(SpotifyCallbackTarget.parse(callback))
        listOf(
            null, "not a uri", callback.replace("https", "http"),
            callback.replace("callback.test.invalid", "attacker.test.invalid"),
            callback.replace("callback.test.invalid", "callback.test.invalid.attacker.invalid"),
            callback.replace("callback.test.invalid", "callback.test.invalid:444"),
            callback.replace("callback.test.invalid", "user@callback.test.invalid"),
            "$callback/", "$callback#access_token=unused",
            callback.replace("/spotify/", "/other/"),
            callback.replace("callback.html", "%63allback.html")
        ).forEach { assertFalse(it, target.accepts(it)) }
    }
}
