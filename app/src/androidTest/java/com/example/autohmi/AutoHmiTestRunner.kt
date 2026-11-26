package com.example.autohmi

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.example.autohmi.music.SpotifyConfiguration
import com.example.autohmi.music.web.SpotifyRequest
import com.example.autohmi.music.web.SpotifyResponse
import com.example.autohmi.music.web.SpotifySecretStore
import com.example.autohmi.music.web.SpotifySecrets
import com.example.autohmi.music.web.SpotifyTransport
import com.example.autohmi.music.web.SpotifyWebRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.autohmi.navigation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Instrumentation cannot read the owner's encrypted session or call Spotify. */
class AutoHmiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, AutoHmiTestApplication::class.java.name, context)
}

class AutoHmiTestApplication : Application(), SpotifyWebOwner, NavigationOwner {
    override fun createNavigationDependencies() = NavigationDependencies(TestLocations(), UnconfiguredRouteRepository(), MapMode.OFFLINE_TEST)
    override val spotifyWebRepository by lazy {
        SpotifyWebRepository(
            SpotifyConfiguration("0123456789abcdef0123456789abcdef", "https://callback.test.invalid/spotify/callback.html"),
            object : SpotifyTransport {
                override suspend fun execute(request: SpotifyRequest) = when {
                    request.url.endsWith("/devices") -> SpotifyResponse(200, "{\"devices\":[]}")
                    request.url.endsWith("/me/player") -> SpotifyResponse(204)
                    else -> SpotifyResponse(400)
                }
                override suspend fun artwork(url: String): ByteArray? = null
            },
            object : SpotifySecretStore {
                private var value = SpotifySecrets()
                override suspend fun read() = value
                override suspend fun write(value: SpotifySecrets) { this.value = value }
            }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        )
    }
}

/** No GPS, permissions or coordinates are read from the device by tests. */
class TestLocations : LocationRepository {
    private val current = MutableStateFlow(LocationState())
    override val state = current.asStateFlow()
    var tracking = false
        private set
    override fun setPermission(permission: LocationPermission) { current.value = current.value.copy(permission = permission) }
    override fun setActive(active: Boolean) { tracking = active; current.value = current.value.copy(status = if (active) LocationStatus.DENIED else LocationStatus.IDLE) }
    override fun requestFresh() = Unit
    override fun close() { tracking = false }
}
