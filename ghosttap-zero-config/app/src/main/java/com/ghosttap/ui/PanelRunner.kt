package com.ghosttap.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Package 2 (A-12) — one shared "panel runner" for the target screen.
 *
 * The target screen is long and type-dependent, so the same action used
 * to live in a different place per device type and the user had to scroll
 * to find it. Every panel on the target screen registers its position
 * here (via [PanelAnchor]); anything that opens a panel — an action row,
 * the "do this" shortlist, the "what to try next" suggestion — jumps
 * straight to it through [jump].
 *
 * Jumping only moves the screen. It never runs a write, a send, or a
 * connect by itself — the user still taps the control once they are
 * looking at it.
 */
class PanelRunner {
    /** The target screen's scroll state, bound when the screen composes. */
    var scrollState: ScrollState? = null

    private val offsets = mutableMapOf<String, Int>()

    /** Records a panel's top edge, in pixels from the top of the screen's content. */
    fun register(key: String, yPx: Int) {
        offsets[key] = yPx
    }

    /** Scrolls so the named panel is at the top of the screen. No-op when unknown. */
    fun jump(scope: CoroutineScope, key: String) {
        val target = offsets[key] ?: return
        val state = scrollState ?: return
        scope.launch { state.scrollTo((target - 8).coerceAtLeast(0)) }
    }
}

/**
 * Wraps one target-screen panel and records where it sits, so the
 * [PanelRunner] can jump to it. Adds no layout of its own — the panel
 * keeps its size, order, and appearance.
 */
@Composable
fun PanelAnchor(key: String, runner: PanelRunner, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            runner.register(key, coords.boundsInParent().top.toInt())
        }
    ) {
        content()
    }
}
