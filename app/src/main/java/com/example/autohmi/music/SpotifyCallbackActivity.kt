package com.example.autohmi.music

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.example.autohmi.BuildConfig
import com.example.autohmi.MainActivity
import com.example.autohmi.spotifyWebRepository

/** Consumes the OAuth response in the app repository; never forwards it to UI. */
class SpotifyCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.dataString
        val target = SpotifyCallbackTarget.parse(BuildConfig.SPOTIFY_REDIRECT_URI)
        if (BuildConfig.SPOTIFY_APP_LINKS_ENABLED && intent.action == Intent.ACTION_VIEW &&
            target?.accepts(raw) == true
        ) {
            // Clear the Activity's stored intent before leaving this transient entry point.
            intent.data = null
            setIntent(Intent())
            application.spotifyWebRepository().handleCallback(raw)
            // Start a clean explicit intent; never forward the incoming URL, extras or OAuth data.
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SPOTIFY
                // Return to AutoHMI's task even if another app opened this link in its task.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        finish()
    }
}
