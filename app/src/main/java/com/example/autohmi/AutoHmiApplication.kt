package com.example.autohmi

import android.app.Application
import android.content.Context
import com.example.autohmi.music.SpotifyConfiguration
import com.example.autohmi.music.web.AndroidSpotifySecretStore
import com.example.autohmi.music.web.SpotifyHttpTransport
import com.example.autohmi.music.web.SpotifyWebRepository
import com.example.autohmi.music.pairing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.autohmi.navigation.NavigationOwner
import com.example.autohmi.navigation.productionNavigationDependencies

/** Tests supply an application-owned fake repository, never the owner's real session. */
interface SpotifyWebOwner {
    val spotifyWebRepository: SpotifyWebRepository
    // Test applications remain offline unless they explicitly supply a mocked pairing transport.
    fun createSpotifyPairing(scope: CoroutineScope) = SpotifyPairingController(
        PairingConfiguration(""), "", spotifyWebRepository, UnconfiguredPairingTransport(), scope)
}
fun Context.spotifyWebRepository(): SpotifyWebRepository = (applicationContext as SpotifyWebOwner).spotifyWebRepository
fun Context.createSpotifyPairing(scope: CoroutineScope) = (applicationContext as SpotifyWebOwner).createSpotifyPairing(scope)

class AutoHmiApplication : Application(), SpotifyWebOwner, NavigationOwner {
    override val completedTripRecorder by lazy {
        val database = AutoHmiDatabase.getInstance(this)
        CompletedTripRecorder(TripRepository(database.tripDao(), database.completedNavigationTripDao()),
            CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
    override fun createSpotifyPairing(scope: CoroutineScope): SpotifyPairingController {
        val config = PairingConfiguration(BuildConfig.SPOTIFY_PAIRING_BASE_URL)
        return SpotifyPairingController(config, BuildConfig.SPOTIFY_CLIENT_ID, spotifyWebRepository, PairingHttpTransport(config), scope)
    }
    override fun createNavigationDependencies() = productionNavigationDependencies(this)
    override val spotifyWebRepository by lazy {
        SpotifyWebRepository(
            SpotifyConfiguration(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI),
            SpotifyHttpTransport(), AndroidSpotifySecretStore(this),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        )
    }
}
