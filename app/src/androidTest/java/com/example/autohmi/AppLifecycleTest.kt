package com.example.autohmi

import androidx.compose.ui.test.assertIsDisplayed
import com.example.autohmi.createDisplayComposeRule as createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.example.autohmi.music.MusicViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.example.autohmi.music.SpotifyCallbackActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityStartsAndRecreationPreservesActiveTripAndViewModel() {
        compose.onNodeWithText("Vehicle overview").assertIsDisplayed()
        compose.onNodeWithText("Accelerate +10").performClick()
        lateinit var original: VehicleViewModel
        lateinit var originalMusic: MusicViewModel
        compose.activityRule.scenario.onActivity {
            original = ViewModelProvider(it)[VehicleViewModel::class.java]
            originalMusic = ViewModelProvider(it)[MusicViewModel::class.java]
            assertEquals(10, original.vehicleStatus.value.speedKmh)
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            assertTrue(original === ViewModelProvider(it)[VehicleViewModel::class.java])
            assertTrue(originalMusic === ViewModelProvider(it)[MusicViewModel::class.java])
            assertEquals(10, original.vehicleStatus.value.speedKmh)
        }
        compose.onNodeWithText("Vehicle overview").assertIsDisplayed()
    }

    @Test
    fun backgroundingStopsTicksAndForegroundResumesWithoutCatchUp() {
        lateinit var viewModel: VehicleViewModel
        compose.activityRule.scenario.onActivity {
            viewModel = ViewModelProvider(it)[VehicleViewModel::class.java]
            repeat(6) { _ -> viewModel.accelerate() }
            viewModel.toggleSimulation()
        }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.vehicleStatus.value.distanceKm > 0.0 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val before = viewModel.vehicleStatus.value.distanceKm
        // Wall time is intentional here: this checks the real Activity/coroutine integration.
        Thread.sleep(2_200)
        assertEquals(before, viewModel.vehicleStatus.value.distanceKm, 0.0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.vehicleStatus.value.distanceKm > before }
        compose.activityRule.scenario.onActivity { viewModel.toggleSimulation() }
        assertEquals(before + 1.0, viewModel.vehicleStatus.value.distanceKm, 0.000001)
    }

    @Test
    fun callbackReturnNavigationRetainsTheExistingVehicleViewModel() {
        lateinit var original: VehicleViewModel
        lateinit var originalMusic: MusicViewModel
        compose.activityRule.scenario.onActivity {
            original = ViewModelProvider(it)[VehicleViewModel::class.java]
            originalMusic = ViewModelProvider(it)[MusicViewModel::class.java]
            original.accelerate()
            it.startActivity(Intent(it, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_MUSIC
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        compose.onNodeWithText("Add audio files").assertIsDisplayed()
        compose.activityRule.scenario.onActivity {
            assertTrue(original === ViewModelProvider(it)[VehicleViewModel::class.java])
            assertTrue(originalMusic === ViewModelProvider(it)[MusicViewModel::class.java])
            assertEquals(10, original.vehicleStatus.value.speedKmh)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun callbackExposureMatchesWhetherAHostWasConfigured() {
        compose.activityRule.scenario.onActivity {
            val info = it.packageManager.getActivityInfo(
                ComponentName(it, SpotifyCallbackActivity::class.java),
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
            assertEquals(BuildConfig.SPOTIFY_APP_LINKS_ENABLED, info.enabled)
            assertEquals(BuildConfig.SPOTIFY_APP_LINKS_ENABLED, info.exported)
        }
    }
}
