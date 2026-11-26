package com.example.autohmi.ui

import org.junit.Assert.*
import org.junit.Test

class AdaptiveWindowTest {
    @Test fun phonePortraitAndLandscapeRetainCompactNavigation() {
        assertFalse(usesExpandedLayout(393f, 780f))
        assertFalse(usesExpandedLayout(900f, 380f))
    }
    @Test fun automotiveAndLargeWindowsUseHorizontalSpace() {
        assertTrue(usesExpandedLayout(1408f, 620f))
        assertTrue(usesExpandedLayout(1024f, 700f))
    }
    @Test fun compactHeightAndExpandedWidthBoundariesAreIndependent() {
        assertFalse(usesExpandedLayout(839f, 620f))
        assertFalse(usesExpandedLayout(900f, 479f))
        assertTrue(usesExpandedLayout(840f, 480f))
    }
    @Test fun shortWideOemScaledWindowsKeepAutomotiveCompositionWithoutADeviceCheck() {
        assertTrue(usesExpandedLayout(984f, 433f))
        assertTrue(usesExpandedLayout(960f, 400f))
        assertFalse(usesExpandedLayout(959f, 433f))
        assertFalse(usesExpandedLayout(960f, 399f))
    }
}
