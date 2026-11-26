package com.example.autohmi.music.web

import com.example.autohmi.music.MusicPlayerState
import com.example.autohmi.music.MusicTrack
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.flag(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
internal fun JsonObject.number(key: String): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0
internal fun JsonObject.child(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

object SpotifyJson {
    fun tokens(body: String, now: Long, previousRefresh: String? = null): OAuthTokens {
        val json = JsonParser.parseString(body).asJsonObject
        val access = json.text("access_token")
        val refresh = json.text("refresh_token").ifBlank { previousRefresh.orEmpty() }
        require(access.isNotBlank() && refresh.isNotBlank() && json.text("token_type").equals("Bearer", true)) { "Invalid token response" }
        val duration = json.number("expires_in")
        require(duration in 1..86400) { "Invalid token lifetime" }
        if (json.has("scope")) require(json.text("scope").split(' ').containsAll(SpotifyPkce.SCOPES.split(' '))) { "Missing playback scope" }
        return OAuthTokens(access, refresh, now + duration * 1000)
    }
    fun device(json: JsonObject?): ConnectDevice? {
        if (json == null || json.text("id").isBlank()) return null
        return ConnectDevice(json.text("id"), json.text("name").ifBlank { "Spotify device" }, json.text("type"), json.flag("is_active"), json.flag("is_restricted"))
    }
    fun devices(body: String): List<ConnectDevice> = JsonParser.parseString(body).asJsonObject
        .getAsJsonArray("devices").mapNotNull { device(it.asJsonObject) }.distinctBy { it.id }
    fun playback(body: String): PlaybackSnapshot {
        val json = JsonParser.parseString(body).asJsonObject
        val item = json.child("item")
        val device = device(json.child("device"))
        val actions = json.child("actions")
        val disallowed = actions?.child("disallows") ?: actions
        fun allowed(key: String) = device?.active == true && !device.restricted && disallowed?.flag(key) != true
        val track = item?.let {
            val album = it.child("album")
            val images = album?.getAsJsonArray("images")
            MusicTrack(it.text("uri"), it.text("name"), it.getAsJsonArray("artists")?.joinToString(", ") { artist -> artist.asJsonObject.text("name") }.orEmpty(),
                it.number("duration_ms").coerceAtLeast(0), images?.firstOrNull()?.asJsonObject?.text("url"))
        }
        val playing = json.flag("is_playing") && track != null
        return PlaybackSnapshot(MusicPlayerState(
            track = track, isPlaying = playing, positionMs = json.number("progress_ms").coerceIn(0, track?.durationMs ?: 0),
            canPlay = track != null && !playing && allowed("resuming"), canPause = playing && allowed("pausing"),
            canNext = track != null && allowed("skipping_next"), canPrevious = track != null && allowed("skipping_prev"),
            canSeek = track != null && allowed("seeking")
        ), device)
    }
}
