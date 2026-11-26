package com.example.autohmi

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import com.example.autohmi.createDisplayComposeRule as createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.autohmi.music.SpotifyCallbackActivity
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SpotifyCallbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun callbackNavigatesWithACleanIntentAndRejectsAnUnsolicitedSyntheticResponse() {
        assumeTrue(BuildConfig.SPOTIFY_APP_LINKS_ENABLED)
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, SpotifyCallbackActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(BuildConfig.SPOTIFY_REDIRECT_URI + "?state=synthetic-unrequested-state&code=synthetic-unused-code")
            })
        }
        compose.onNodeWithText("Spotify Connect").assertIsDisplayed()
        compose.onNodeWithText("Authorize in browser").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { assertNull(it.intent.data) }
    }
}
