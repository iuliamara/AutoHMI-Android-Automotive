package com.example.autohmi.navigation.ui

import org.junit.Assert.*
import org.junit.Test

class SidebarSwipeIntentTest {
    private fun swipe(direction: Int = -1) = SidebarSwipeIntent(direction, slop = 8f, threshold = 64f)

    @Test fun deliberateLeftCollapse() {
        val intent = swipe()
        assertFalse(intent.move(-4f, 2f))
        assertTrue(intent.move(-20f, 3f))
        assertTrue(intent.move(-80f, 10f))
        assertTrue(intent.completed())
    }
    @Test fun deliberateRightExpand() {
        val intent = swipe(1)
        assertTrue(intent.move(64f, 0f))
        assertTrue(intent.completed())
    }
    @Test fun tapsAndShortDragsDoNotToggle() {
        val intent = swipe()
        assertFalse(intent.move(-2f, 1f))
        assertFalse(intent.completed())
        assertTrue(intent.move(-63f, 0f))
        assertFalse(intent.completed())
    }
    @Test fun verticalScrollCannotTurnIntoCollapse() {
        val intent = swipe()
        assertFalse(intent.move(-3f, 20f))
        assertFalse(intent.move(-100f, 22f))
        assertFalse(intent.completed())
    }
    @Test fun diagonalScrollYieldsWithoutConsuming() {
        val intent = swipe()
        assertFalse(intent.move(-14f, 12f))
        assertFalse(intent.move(-100f, 13f))
        assertFalse(intent.completed())
    }
    @Test fun oppositeDirectionDoesNotToggle() {
        val intent = swipe()
        assertFalse(intent.move(80f, 0f))
        assertFalse(intent.completed())
        assertFalse(intent.move(-80f, 0f))
    }
    @Test fun ReversingBelowThresholdDoesNotToggle() {
        val intent = swipe()
        intent.move(-100f, 0f)
        intent.move(-30f, 0f)
        assertFalse(intent.completed())
    }
    @Test fun verticalFinishDoesNotToggle() {
        val intent = swipe()
        intent.move(-20f, 0f)
        intent.move(-80f, 100f)
        assertFalse(intent.completed())
    }
}
