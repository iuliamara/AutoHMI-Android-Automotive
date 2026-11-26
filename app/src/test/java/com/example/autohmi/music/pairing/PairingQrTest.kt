package com.example.autohmi.music.pairing

import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.*
import org.junit.Test

class PairingQrTest {
    @Test fun qrDecodesToOnlyThePublicHttpsPairingHandle() {
        val url = "https://relay.test.invalid/pair/" + "P".repeat(43)
        val result = Decoder().decode(com.google.zxing.qrcode.detector.Detector(PairingQr.encode(url)).detect().bits)
        assertEquals(url, result.text)
        assertFalse(result.text.contains('?')); assertFalse(result.text.contains("token"))
    }
    @Test fun rejectsAuthUrlsCredentialsAndNonHttpsOrigins() {
        for (url in listOf("https://accounts.spotify.com/authorize?code=fixture", "https://relay.test.invalid/pair/${"P".repeat(43)}?token=fixture",
            "http://relay.test.invalid/pair/${"P".repeat(43)}", "https://user:pass@relay.test.invalid/pair/${"P".repeat(43)}")) {
            assertTrue(runCatching { PairingQr.encode(url) }.isFailure)
        }
    }
    @Test fun configurationRequiresAnHttpsOriginWithoutPathOrCredentials() {
        assertTrue(PairingConfiguration("https://relay.test.invalid").isConfigured)
        for (value in listOf("", "http://relay.test.invalid", "https://relay.test.invalid/path", "https://relay.test.invalid?token=x", "https://localhost", "https://u:p@relay.test.invalid")) assertFalse(PairingConfiguration(value).isConfigured)
    }
}
