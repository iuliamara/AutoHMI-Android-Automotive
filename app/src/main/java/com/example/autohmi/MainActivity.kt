package com.example.autohmi

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.autohmi.ui.VehicleScreen
import com.example.autohmi.ui.theme.AutoHMITheme
import com.example.autohmi.music.MusicViewModel
import com.example.autohmi.music.MusicSource
import com.example.autohmi.navigation.NavigationViewModel
import com.example.autohmi.navigation.ui.NavigationScreen

class MainActivity : ComponentActivity() {
    private var openMusicRequest by mutableIntStateOf(0)
    private val musicViewModel: MusicViewModel by viewModels()
    private val navigationViewModel: NavigationViewModel by viewModels()
    private val vehicleViewModel: VehicleViewModel by viewModels {
        viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                val database = AutoHmiDatabase.getInstance(application)
                VehicleViewModel(
                    VehicleSettingsRepository(application),
                    TripRepository(database.tripDao(), database.completedNavigationTripDao())
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleNavigation(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            AutoHMITheme {
                VehicleScreen(vehicleViewModel, musicViewModel, openMusicRequest, navigationContent = { NavigationScreen(navigationViewModel) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigation(intent)
    }

    private fun handleNavigation(intent: Intent) {
        if (intent.action == ACTION_OPEN_SPOTIFY) musicViewModel.session.selectSource(MusicSource.SPOTIFY)
        if (intent.action == ACTION_OPEN_MUSIC || intent.action == ACTION_OPEN_SPOTIFY) openMusicRequest++
    }

    override fun onStart() {
        super.onStart()
        vehicleViewModel.setForeground(true)
        musicViewModel.onStart()
    }

    override fun onStop() {
        vehicleViewModel.setForeground(false)
        musicViewModel.onStop(isChangingConfigurations)
        super.onStop()
    }

    companion object {
        const val ACTION_OPEN_MUSIC = "com.example.autohmi.OPEN_MUSIC"
        const val ACTION_OPEN_SPOTIFY = "com.example.autohmi.OPEN_SPOTIFY"
    }
}
