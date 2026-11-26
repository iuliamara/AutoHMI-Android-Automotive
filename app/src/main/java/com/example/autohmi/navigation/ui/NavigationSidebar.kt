package com.example.autohmi.navigation.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal val SidebarEdgeWidth = 28.dp

/** Single-pointer intent lock: vertical/diagonal motion yields to scrolling for the whole gesture. */
internal class SidebarSwipeIntent(private val direction: Int, private val slop: Float, private val threshold: Float) {
    private var rejected = false
    var horizontal = false
        private set
    private var x = 0f
    private var y = 0f

    fun move(x: Float, y: Float): Boolean {
        this.x = x
        this.y = y
        if (!horizontal && !rejected && maxOf(abs(x), abs(y)) > slop) {
            horizontal = x * direction > 0 && abs(x) >= abs(y) * 1.5f
            rejected = !horizontal
        }
        return horizontal
    }

    fun completed(): Boolean = horizontal && x * direction >= threshold && abs(x) >= abs(y) * 1.5f
}

/** Observe without taking taps; consume only after a deliberate horizontal intent wins. */
@Composable
internal fun Modifier.sidebarSwipe(
    direction: Int,
    enabled: Boolean = true,
    startZone: Dp? = null,
    onSwipe: () -> Unit
): Modifier {
    val action by rememberUpdatedState(onSwipe)
    return if (!enabled) this else pointerInput(direction, startZone) {
        val threshold = 64.dp.toPx()
        val zone = startZone?.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (zone != null && down.position.x !in 0f..zone) return@awaitEachGesture
            val intent = SidebarSwipeIntent(direction, viewConfiguration.touchSlop, threshold)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) break // Pinches never change panel visibility.
                val change = event.changes.first()
                if (change.id != down.id || change.isConsumed) break
                val delta = change.position - down.position
                if (intent.move(delta.x, delta.y)) change.consume()
                if (!change.pressed) {
                    if (intent.completed()) action()
                    break
                }
            }
        }
    }
}

/** Keep the full-width panel and its scroll state mounted behind a sliding, clipped viewport. */
@Composable
internal fun SlidingNavigationPanel(
    collapsed: Boolean,
    fullWidth: Dp,
    onCollapse: () -> Unit,
    panel: @Composable (Modifier) -> Unit
) {
    val width by animateDpAsState(if (collapsed) 0.dp else fullWidth,
        tween(280, easing = FastOutSlowInEasing), label = "Navigation sidebar width")
    Layout(content = { panel(Modifier.fillMaxSize()) }, modifier = Modifier.width(width).fillMaxHeight()
        .clipToBounds().testTag("navigation_sidebar_viewport")
        .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier)
        .sidebarSwipe(direction = -1, enabled = !collapsed, onSwipe = onCollapse)) { children, constraints ->
        val child = children.single().measure(Constraints.fixed(fullWidth.roundToPx(), constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.placeRelative(constraints.maxWidth - child.width, 0)
        }
    }
}
