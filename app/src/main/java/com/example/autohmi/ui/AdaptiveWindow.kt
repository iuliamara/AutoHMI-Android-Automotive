package com.example.autohmi.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Roomy expanded or short-wide windows, measured after safe drawing insets.
 * No device pixels, orientation lock, hardware-name check or density override. */
fun usesExpandedLayout(widthDp: Float, heightDp: Float): Boolean =
    (widthDp >= 840f && heightDp >= 480f) || (widthDp >= 960f && heightDp >= 400f)

internal val LocalExpandedWindow = staticCompositionLocalOf { false }
