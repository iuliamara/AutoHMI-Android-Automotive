package com.example.autohmi.music.web

import com.example.autohmi.music.MusicCommand
import com.example.autohmi.music.MusicIssue
import com.example.autohmi.music.MusicPlayerState
import com.example.autohmi.music.SpotifyConfiguration
import com.example.autohmi.music.SpotifyConnectionState
import com.example.autohmi.music.SpotifyConnectionStatus
import com.example.autohmi.music.SpotifyController
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** App-scoped, browser-authorized Connect controller. Never logs HTTP/auth data. */
class SpotifyWebRepository(
    private val configuration: SpotifyConfiguration,
    private val transport: SpotifyTransport,
    private val store: SpotifySecretStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) : SpotifyController {
    private val mutex = Mutex()
    private val _web = MutableStateFlow(SpotifyWebState())
    val web = _web.asStateFlow()
    private val _state = MutableStateFlow(MusicPlayerState())
    override val state = _state.asStateFlow()
    private val _connection = MutableStateFlow(SpotifyConnectionState())
    override val connection = _connection.asStateFlow()
    private var secrets = SpotifySecrets()
    private var foreground = false
    private var poll: Job? = null
    private var sampledPlayer = MusicPlayerState()
    private var sampleTime = 0L
    private var lastDevicesAt = 0L
    private var retryAt = 0L
    private var artworkJob: Job? = null
    private var refreshJob: Job? = null

    private class Failure(val issue: WebIssue) : Exception("Spotify operation failed")
    private val ready = scope.async {
        try {
            secrets = store.read()
            if (!configuration.isValid) {
                signedOut(WebIssue.CONFIGURATION)
            } else {
                // QR session secrets are memory-only. A process restart abandons incomplete QR pairing.
                val pending = secrets.pending?.takeIf { it.kind == AuthorizationKind.BROWSER && now() - it.createdAtMs in 0..SpotifyPkce.MAX_AGE_MS }
                if (pending !== secrets.pending) save(SpotifySecrets(secrets.tokens, pending))
                _web.update { it.copy(auth = when { secrets.tokens != null -> WebAuthStatus.AUTHORIZED; pending != null -> WebAuthStatus.AUTHORIZING; else -> WebAuthStatus.SIGNED_OUT }) }
                updateConnection()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { signedOut(WebIssue.STORAGE) }
    }

    private suspend fun save(value: SpotifySecrets) {
        try { store.write(value) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw Failure(WebIssue.STORAGE) }
        secrets = value
    }
    private fun signedOut(issue: WebIssue? = null) {
        _state.value = MusicPlayerState()
        sampledPlayer = MusicPlayerState()
        artworkJob?.cancel()
        _web.value = SpotifyWebState(auth = WebAuthStatus.SIGNED_OUT, issue = issue, retryAtMs = retryAt)
        updateConnection()
    }
    private fun updateConnection() {
        _connection.value = SpotifyConnectionState(
            when (_web.value.auth) {
                WebAuthStatus.AUTHORIZED -> SpotifyConnectionStatus.CONNECTED
                WebAuthStatus.AUTHORIZING, WebAuthStatus.EXCHANGING, WebAuthStatus.RESTORING -> SpotifyConnectionStatus.CONNECTING
                WebAuthStatus.SIGNED_OUT -> SpotifyConnectionStatus.DISCONNECTED
            }, if (_web.value.issue == WebIssue.CONFIGURATION) MusicIssue.CONFIGURATION else null
        )
    }

    suspend fun prepareAuthorization(): String? {
        ready.await()
        return mutex.withLock {
            if (!configuration.isValid) { signedOut(WebIssue.CONFIGURATION); return@withLock null }
            if (_web.value.auth in setOf(WebAuthStatus.AUTHORIZING, WebAuthStatus.EXCHANGING, WebAuthStatus.AUTHORIZED)) return@withLock null
            try {
                val pending = SpotifyPkce.pending(now())
                save(SpotifySecrets(pending = pending))
                signedOut()
                _web.update { it.copy(auth = WebAuthStatus.AUTHORIZING) }
                updateConnection()
                SpotifyPkce.authorizationUrl(configuration.clientId, configuration.redirectUri, pending)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { signedOut(WebIssue.STORAGE); null }
        }
    }

    /** Only a challenge leaves the encrypted PKCE store; never exposes its verifier. */
    internal suspend fun prepareQrAuthorization(redirectUri: String): QrAuthorizationMaterial? {
        ready.await()
        return mutex.withLock {
            if (!configuration.isValid || com.example.autohmi.music.SpotifyCallbackTarget.parse(redirectUri) == null ||
                java.net.URI(redirectUri).path != "/oauth/spotify/callback") return@withLock null
            if (_web.value.auth != WebAuthStatus.SIGNED_OUT) return@withLock null
            val value = SpotifyPkce.pending(now())
            val pending = PendingAuthorization(value.state, value.verifier, value.createdAtMs, AuthorizationKind.QR, redirectUri)
            save(SpotifySecrets(pending = pending))
            signedOut()
            _web.update { it.copy(auth = WebAuthStatus.AUTHORIZING) }
            updateConnection()
            QrAuthorizationMaterial(pending.state, SpotifyPkce.challenge(pending.verifier))
        }
    }

    internal suspend fun bindQrAuthorization(nonce: String, state: String): Boolean {
        ready.await()
        return mutex.withLock {
            val pending = secrets.pending ?: return@withLock false
            if (pending.kind != AuthorizationKind.QR || pending.state != nonce ||
                !state.matches(Regex("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")) || state.substringAfter('.') != nonce ||
                now() - pending.createdAtMs !in 0..SpotifyPkce.QR_MAX_AGE_MS) return@withLock false
            save(SpotifySecrets(pending = PendingAuthorization(state, pending.verifier, pending.createdAtMs, pending.kind, pending.redirectUri)))
            true
        }
    }

    internal suspend fun cancelQrAuthorization(expectedState: String) {
        ready.await()
        mutex.withLock {
            val pending = secrets.pending ?: return@withLock
            if (pending.kind != AuthorizationKind.QR || pending.state != expectedState) return@withLock
            save(SpotifySecrets(tokens = secrets.tokens))
            if (secrets.tokens == null) signedOut(WebIssue.AUTH_CANCELLED)
        }
    }

    /** Strict single-use completion. QR callbacks cannot enter through the exported App Link Activity. */
    internal suspend fun completeQrAuthorization(expectedState: String, code: String): WebIssue? {
        ready.await()
        val issue = mutex.withLock {
            val pending = secrets.pending
            if (pending == null || pending.kind != AuthorizationKind.QR || pending.state != expectedState ||
                code.isBlank() || code.length > 2048 || code.any { it == '\r' || it == '\n' }) return@withLock WebIssue.CALLBACK_INVALID
            try {
                save(SpotifySecrets(tokens = secrets.tokens))
                if (now() - pending.createdAtMs !in 0..SpotifyPkce.QR_MAX_AGE_MS) {
                    signedOut(WebIssue.AUTH_EXPIRED); return@withLock WebIssue.AUTH_EXPIRED
                }
                _web.update { it.copy(auth = WebAuthStatus.EXCHANGING, issue = null, busy = true) }
                updateConnection()
                enforceBackoff()
                val response = execute(SpotifyRequest("POST", TOKEN_URL, form = mapOf(
                    "grant_type" to "authorization_code", "code" to code,
                    "redirect_uri" to requireNotNull(pending.redirectUri), "client_id" to configuration.clientId,
                    "code_verifier" to pending.verifier
                )))
                if (response.status != 200) throw Failure(WebIssue.AUTH_FAILED)
                save(SpotifySecrets(tokens = SpotifyJson.tokens(response.body, now())))
                _web.update { it.copy(auth = WebAuthStatus.AUTHORIZED, busy = false, issue = null) }
                updateConnection()
                null
            } catch (cancelled: CancellationException) { signedOut(WebIssue.AUTH_CANCELLED); throw cancelled }
            catch (failure: Failure) { signedOut(failure.issue); failure.issue }
            catch (_: Exception) { signedOut(WebIssue.AUTH_FAILED); WebIssue.AUTH_FAILED }
        }
        if (issue == null) ensurePolling()
        return issue
    }

    fun cancelAuthorization(issue: WebIssue = WebIssue.AUTH_CANCELLED) = scope.launch {
        ready.await()
        mutex.withLock {
            try { save(SpotifySecrets(tokens = secrets.tokens)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { signedOut(WebIssue.STORAGE); return@withLock }
            if (secrets.tokens == null) signedOut(issue)
        }
    }

    /** URI is consumed here and is never forwarded to UI, logs or saved storage. */
    fun handleCallback(raw: String?) = scope.launch {
        ready.await()
        mutex.withLock {
            val pending = secrets.pending
            val callback = SpotifyPkce.callback(raw, configuration.redirectUri, pending, now())
            try {
                if (callback.consume) save(SpotifySecrets(tokens = secrets.tokens)) // single use, even if exchange fails
                if (callback.issue != null) {
                    _web.update { it.copy(issue = callback.issue, auth = if (secrets.pending != null) WebAuthStatus.AUTHORIZING else if (secrets.tokens != null) WebAuthStatus.AUTHORIZED else WebAuthStatus.SIGNED_OUT) }
                    updateConnection()
                    return@withLock
                }
                _web.update { it.copy(auth = WebAuthStatus.EXCHANGING, issue = null, busy = true) }
                updateConnection()
                enforceBackoff()
                val result = execute(SpotifyRequest("POST", TOKEN_URL, form = mapOf(
                    "grant_type" to "authorization_code", "code" to requireNotNull(callback.code),
                    "redirect_uri" to configuration.redirectUri, "client_id" to configuration.clientId,
                    "code_verifier" to requireNotNull(pending).verifier
                )))
                if (result.status != 200) throw Failure(WebIssue.AUTH_FAILED)
                val tokens = SpotifyJson.tokens(result.body, now())
                save(SpotifySecrets(tokens = tokens))
                _web.update { it.copy(auth = WebAuthStatus.AUTHORIZED, issue = null, busy = false) }
                updateConnection()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Failure) { signedOut(failure.issue) }
            catch (_: Exception) { signedOut(WebIssue.AUTH_FAILED) }
        }
        ensurePolling()
    }

    fun logout() = scope.launch {
        ready.await()
        poll?.cancel(); poll = null
        artworkJob?.cancel()
        _web.update { it.copy(busy = true) }
        mutex.withLock {
            try { save(SpotifySecrets()); signedOut() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { secrets = SpotifySecrets(); signedOut(WebIssue.STORAGE) }
        }
    }
    override fun disconnect() { logout() }
    override fun connect(showAuth: Boolean) { refreshNow() } // Browser launch requires an explicit UI action.
    override fun release() { setForeground(false) } // Session persists encrypted; logout is explicit.
    override fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { poll?.cancel(); poll = null }
        else ensurePolling()
    }
    private fun ensurePolling() {
        if (!foreground || poll?.isActive == true) return
        poll = scope.launch {
            ready.await()
            var nextFetch = 0L
            while (isActive && foreground && _web.value.auth == WebAuthStatus.AUTHORIZED) {
                if (now() >= nextFetch && now() >= _web.value.retryAtMs) {
                    synchronize(forceDevices = false)
                    nextFetch = now() + if (_web.value.issue == WebIssue.NETWORK || _web.value.issue == WebIssue.SERVICE) 15_000 else 5_000
                }
                updateProgress()
                delay(1_000)
            }
        }
    }
    fun refreshNow(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch { ready.await(); synchronize(forceDevices = true); ensurePolling() }.also { refreshJob = it }
    }

    private fun updateProgress() {
        val age = (now() - sampleTime).coerceAtLeast(0)
        if (age > 15_000) {
            _web.update { it.copy(stale = true) }
            _state.update { it.copy(canPlay = false, canPause = false, canNext = false, canPrevious = false, canSeek = false) }
            return
        }
        if (sampledPlayer.isPlaying) _state.update { it.copy(positionMs = (sampledPlayer.positionMs + age).coerceAtMost(sampledPlayer.track?.durationMs ?: 0)) }
    }

    private suspend fun synchronize(forceDevices: Boolean) = mutex.withLock {
        if (secrets.tokens == null || _web.value.auth != WebAuthStatus.AUTHORIZED) return@withLock
        try {
            enforceBackoff()
            if (forceDevices || lastDevicesAt == 0L || now() - lastDevicesAt >= 15_000) {
                val response = api("GET", "/me/player/devices")
                val devices = SpotifyJson.devices(response.body)
                lastDevicesAt = now()
                _web.update { it.copy(devices = devices) }
            }
            val response = api("GET", "/me/player")
            val playback = if (response.status == 204) PlaybackSnapshot(MusicPlayerState(), null) else SpotifyJson.playback(response.body)
            val oldArtworkKey = _state.value.track?.artworkKey
            sampledPlayer = playback.player
            sampleTime = now()
            val selectedId = _web.value.selectedDeviceId ?: playback.device?.id
            val selected = _web.value.devices.find { it.id == selectedId }
            val mismatch = selectedId != null && selectedId != playback.device?.id
            _state.value = playback.player.copy(
                artworkBytes = if (oldArtworkKey == playback.player.track?.artworkKey) _state.value.artworkBytes else null,
                canPlay = !mismatch && playback.player.canPlay, canPause = !mismatch && playback.player.canPause,
                canNext = !mismatch && playback.player.canNext, canPrevious = !mismatch && playback.player.canPrevious,
                canSeek = !mismatch && playback.player.canSeek
            )
            _web.update { it.copy(playbackDevice = playback.device, selectedDeviceId = selectedId, lastUpdatedMs = now(), stale = false,
                issue = when {
                    selectedId != null && selected == null -> WebIssue.DEVICE_UNAVAILABLE
                    selected?.restricted == true -> WebIssue.PLAYBACK_RESTRICTED
                    playback.device == null -> WebIssue.NO_DEVICE
                    !playback.device.active || mismatch -> WebIssue.DEVICE_UNAVAILABLE
                    else -> null
                }) }
            if (oldArtworkKey != playback.player.track?.artworkKey || (_state.value.artworkBytes == null && artworkJob?.isActive != true)) loadArtwork(playback.player.track?.artworkKey)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Failure) { showFailure(failure.issue) }
        catch (_: Exception) { showFailure(WebIssue.SERVICE) }
    }

    private fun loadArtwork(url: String?) {
        artworkJob?.cancel()
        if (url == null) return
        artworkJob = scope.launch {
            val bytes = try { transport.artwork(url) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (_state.value.track?.artworkKey == url) _state.update { it.copy(artworkBytes = bytes) }
        }
    }
    private fun showFailure(issue: WebIssue) {
        _web.update { it.copy(issue = issue, stale = true, busy = false) }
        _state.update { it.copy(canPlay = false, canPause = false, canNext = false, canPrevious = false, canSeek = false) }
    }
    private fun enforceBackoff() { if (now() < retryAt) throw Failure(WebIssue.RATE_LIMITED) }
    private suspend fun execute(request: SpotifyRequest): SpotifyResponse {
        val response = try { transport.execute(request) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { throw Failure(WebIssue.NETWORK) }
        coroutineContext.ensureActive()
        if (response.status == 429) {
            val seconds = (response.retryAfterSeconds ?: 30).coerceIn(1, (Long.MAX_VALUE - now()) / 1000)
            retryAt = now() + seconds * 1000
            _web.update { it.copy(retryAtMs = retryAt, issue = WebIssue.RATE_LIMITED) }
            throw Failure(WebIssue.RATE_LIMITED)
        }
        return response
    }
    private suspend fun refreshAccess(): OAuthTokens {
        enforceBackoff()
        val current = secrets.tokens ?: throw Failure(WebIssue.SESSION_EXPIRED)
        val response = execute(SpotifyRequest("POST", TOKEN_URL, form = mapOf(
            "grant_type" to "refresh_token", "refresh_token" to current.refresh, "client_id" to configuration.clientId
        )))
        if (response.status == 400 || response.status == 401) {
            save(SpotifySecrets()); signedOut(WebIssue.SESSION_EXPIRED)
            throw Failure(WebIssue.SESSION_EXPIRED)
        }
        if (response.status != 200) throw Failure(WebIssue.SERVICE)
        val tokens = try { SpotifyJson.tokens(response.body, now(), current.refresh) }
        catch (_: IllegalArgumentException) {
            save(SpotifySecrets()); signedOut(WebIssue.SESSION_EXPIRED)
            throw Failure(WebIssue.SESSION_EXPIRED)
        }
        save(SpotifySecrets(tokens = tokens))
        return tokens
    }
    private suspend fun api(method: String, path: String, json: String? = null): SpotifyResponse {
        enforceBackoff()
        var tokens = secrets.tokens ?: throw Failure(WebIssue.SESSION_EXPIRED)
        if (tokens.expiresAtMs - now() < 60_000) tokens = refreshAccess()
        var response = execute(SpotifyRequest(method, API_URL + path, bearer = tokens.access, json = json))
        if (response.status == 401) {
            tokens = refreshAccess()
            response = execute(SpotifyRequest(method, API_URL + path, bearer = tokens.access, json = json))
        }
        when (response.status) {
            in 200..299 -> return response
            401 -> { save(SpotifySecrets()); signedOut(WebIssue.SESSION_EXPIRED); throw Failure(WebIssue.SESSION_EXPIRED) }
            403 -> throw Failure(WebIssue.ACCESS_DENIED)
            404 -> throw Failure(WebIssue.DEVICE_UNAVAILABLE)
            else -> throw Failure(WebIssue.SERVICE)
        }
    }

    fun selectDevice(id: String) = scope.launch {
        ready.await()
        var success = false
        mutex.withLock {
            val device = _web.value.devices.find { it.id == id } ?: return@withLock showFailure(WebIssue.DEVICE_UNAVAILABLE)
            if (device.restricted) return@withLock showFailure(WebIssue.PLAYBACK_RESTRICTED)
            _web.update { it.copy(busy = true, issue = null) }
            try {
                val body = JsonObject().apply { add("device_ids", JsonArray().apply { add(id) }); addProperty("play", false) }
                api("PUT", "/me/player", body.toString())
                _web.update { it.copy(selectedDeviceId = id, busy = false) }
                success = true
                _state.update { it.copy(canPlay = false, canPause = false, canNext = false, canPrevious = false, canSeek = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Failure) { showFailure(failure.issue) }
            catch (_: Exception) { showFailure(WebIssue.SERVICE) }
        }
        if (success) synchronize(forceDevices = true) // Transfer is asynchronous. Only observed state enables controls.
    }
    private fun command(command: MusicCommand, allowed: Boolean, position: Long = 0) = scope.launch {
        ready.await()
        if (!allowed || _web.value.busy || _web.value.stale) return@launch
        var success = false
        mutex.withLock {
            if (_web.value.auth != WebAuthStatus.AUTHORIZED || _web.value.stale || _web.value.busy) return@withLock
            val current = _state.value
            val stillAllowed = when (command) {
                MusicCommand.PLAY -> current.canPlay
                MusicCommand.PAUSE -> current.canPause
                MusicCommand.NEXT -> current.canNext
                MusicCommand.PREVIOUS -> current.canPrevious
                MusicCommand.SEEK -> current.canSeek
            }
            if (!stillAllowed) return@withLock
            val id = _web.value.selectedDeviceId ?: return@withLock showFailure(WebIssue.NO_DEVICE)
            if (_web.value.playbackDevice?.id != id) return@withLock showFailure(WebIssue.DEVICE_UNAVAILABLE)
            _web.update { it.copy(busy = true) }
            try {
                val query = "device_id=${SpotifyPkce.encode(id)}"
                val (method, path) = when (command) {
                    MusicCommand.PLAY -> "PUT" to "/me/player/play?$query"
                    MusicCommand.PAUSE -> "PUT" to "/me/player/pause?$query"
                    MusicCommand.NEXT -> "POST" to "/me/player/next?$query"
                    MusicCommand.PREVIOUS -> "POST" to "/me/player/previous?$query"
                    MusicCommand.SEEK -> "PUT" to "/me/player/seek?$query&position_ms=${position.coerceIn(0, _state.value.track?.durationMs ?: 0)}"
                }
                api(method, path) // Never retry ambiguous network/5xx failures or skip commands after 429.
                _web.update { it.copy(busy = false, issue = null) }
                success = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Failure) { showFailure(failure.issue) }
            catch (_: Exception) { showFailure(WebIssue.SERVICE) }
        }
        if (success) synchronize(forceDevices = false)
    }
    override fun play() { command(MusicCommand.PLAY, _state.value.canPlay) }
    override fun pause() { command(MusicCommand.PAUSE, _state.value.canPause) }
    override fun next() { command(MusicCommand.NEXT, _state.value.canNext) }
    override fun previous() { command(MusicCommand.PREVIOUS, _state.value.canPrevious) }
    override fun seekTo(positionMs: Long) { command(MusicCommand.SEEK, _state.value.canSeek, positionMs) }

    companion object {
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val API_URL = "https://api.spotify.com/v1"
    }
}
