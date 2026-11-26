package com.example.autohmi.music.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keystore AES-GCM; ciphertext in noBackupFilesDir. Never falls back to plaintext. */
class AndroidSpotifySecretStore(
    context: Context,
    private val alias: String = "AutoHMI.SpotifyWeb.v1",
    fileName: String = "spotify-web-session.enc"
) : SpotifySecretStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val aad = "AutoHMI.SpotifyWeb.v1".toByteArray(Charsets.UTF_8)
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun read(): SpotifySecrets = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext SpotifySecrets()
        val bytes = file.readFully()
        require(bytes.size in 30..32768 && bytes[0] == 1.toByte()) { "Invalid encrypted session" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try {
            val json = JsonParser.parseString(plaintext.toString(Charsets.UTF_8)).asJsonObject
            val tokens = json.child("tokens")?.let { OAuthTokens(it.text("access"), it.text("refresh"), it.number("expires")) }
            val pending = json.child("pending")?.let { PendingAuthorization(it.text("state"), it.text("verifier"), it.number("created"),
                if (it.has("kind")) AuthorizationKind.valueOf(it.text("kind")) else AuthorizationKind.BROWSER,
                if (it.has("redirect")) it.text("redirect") else null) }
            SpotifySecrets(tokens, pending)
        } finally { plaintext.fill(0) }
    }
    override suspend fun write(value: SpotifySecrets): Unit = withContext(Dispatchers.IO) {
        if (value.tokens == null && value.pending == null) {
            file.delete()
            // Invalidating the key also prevents reuse if a filesystem deletion failed.
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(!file.baseFile.exists()) { "Unable to clear encrypted session" }
            return@withContext
        }
        val json = JsonObject().apply {
            value.tokens?.let { token -> add("tokens", JsonObject().apply {
                addProperty("access", token.access); addProperty("refresh", token.refresh); addProperty("expires", token.expiresAtMs)
            }) }
            value.pending?.let { pending -> add("pending", JsonObject().apply {
                addProperty("state", pending.state); addProperty("verifier", pending.verifier); addProperty("created", pending.createdAtMs)
                addProperty("kind", pending.kind.name)
                pending.redirectUri?.let { addProperty("redirect", it) }
            }) }
        }
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        } finally { plaintext.fill(0) }
    }
}
