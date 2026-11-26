package com.example.autohmi.music

import androidx.test.platform.app.InstrumentationRegistry
import com.example.autohmi.music.web.AndroidSpotifySecretStore
import com.example.autohmi.music.web.OAuthTokens
import com.example.autohmi.music.web.PendingAuthorization
import com.example.autohmi.music.web.SpotifySecrets
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpotifySecretStoreTest {
    @Test fun keystoreCiphertextSurvivesRecreationRejectsTamperingAndLogoutClearsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val alias = "AutoHMI.test.$suffix"
        val name = "spotify-fixture-$suffix.enc"
        val file = File(context.noBackupFilesDir, name)
        val store = AndroidSpotifySecretStore(context, alias, name)
        try {
            store.write(SpotifySecrets(OAuthTokens("synthetic-access-only", "synthetic-refresh-only", 9000), PendingAuthorization("synthetic-state", "synthetic-verifier", 1000)))
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains("synthetic-access-only"))
            val recreated = AndroidSpotifySecretStore(context, alias, name)
            assertEquals(9000L, recreated.read().tokens?.expiresAtMs)
            assertEquals("synthetic-state", recreated.read().pending?.state)
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertext)
            var rejected = false
            try { recreated.read() } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            store.write(SpotifySecrets())
            assertFalse(file.exists())
            assertNull(store.read().tokens)
        } finally {
            // Delete only this test's random filename/key. Never use production session paths.
            file.delete()
            File(context.noBackupFilesDir, "$name.bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
