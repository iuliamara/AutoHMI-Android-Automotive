package com.example.autohmi.music.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

object SpotifySystemBrowser {
    @Suppress("DEPRECATION")
    fun open(context: Context, authorizationUrl: String): Boolean {
        // Generic HTTPS resolution identifies browsers without launching this URL.
        val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.filter { it != "com.spotify.music" && it != context.packageName }.distinct()
        val browser = browsers.find { it == "com.android.chrome" }
            ?: context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it in browsers }
            ?: browsers.firstOrNull() ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
            true
        }.getOrDefault(false)
    }
}
