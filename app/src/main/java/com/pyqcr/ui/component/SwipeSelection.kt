package com.pyqcr.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize

/**
 * Drag-across-thumbnails selection for the photo grids.
 *
 * In multi-select mode the grid container watches the gesture in the Initial
 * pointer pass, and once the finger has moved past the touch slop it
 * hit-tests every cell's layout bounds and reports the ones the finger
 * crosses. Each reported thumbnail is selected (never deselected), so a
 * single diagonal/linear swipe can pick a whole run of photos.
 *
 * Taps are untouched: nothing is consumed until a real drag starts, and a
 * drag that starts in a gap between cells is left to the grid scroller.
 */
@Stable
class SwipeSelectionState {
    /** Cell bounds in ROOT coordinates, keyed by image URI. */
    internal val cellBounds = mutableStateMapOf<String, Rect>()

    internal var containerOrigin: Offset = Offset.Zero
    internal var containerSize: IntSize = IntSize.Zero

    /** Current finger position in ROOT coordinates; unspecified while idle. */
    var pointer: Offset by mutableStateOf(Offset.Unspecified)
        internal set
    var dragging: Boolean by mutableStateOf(false)
        internal set

    internal fun registerCell(uri: String, bounds: Rect) {
        cellBounds[uri] = bounds
    }

    internal fun unregisterCell(uri: String) {
        cellBounds.remove(uri)
    }

    /** URIs already reported during the current swipe (each cell selected once). */
    private val gestureSelected = mutableSetOf<String>()

    /** True the first time [uri] is crossed in the current gesture. */
    internal fun markSelected(uri: String): Boolean = gestureSelected.add(uri)

    internal fun beginGesture() {
        gestureSelected.clear()
    }

    fun hitTest(point: Offset): String? =
        cellBounds.entries.firstOrNull { it.value.contains(point) }?.key
}

@Composable
fun rememberSwipeSelectionState(): SwipeSelectionState =
    remember { SwipeSelectionState() }

/**
 * Registers a grid cell for swipe hit-testing. Apply to the same modifier that
 * carries the cell's click handling; pass null when selection is inactive.
 */
@Composable
fun Modifier.swipeSelectCell(
    state: SwipeSelectionState?,
    uri: String
): Modifier {
    if (state == null) return this
    DisposableEffect(state, uri) {
        onDispose { state.unregisterCell(uri) }
    }
    return this.onGloballyPositioned { coords ->
        state.registerCell(uri, coords.boundsInRoot())
    }
}

/**
 * Container half of the swipe-selection gesture. [onAutoScroll] receives a
 * signed pixel step while the finger sits near the top/bottom edge, so the
 * grid keeps scrolling and newly revealed thumbnails get selected too.
 */
@Composable
fun Modifier.swipeSelectContainer(
    state: SwipeSelectionState,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAutoScroll: (suspend (Float) -> Unit)? = null
): Modifier {
    if (!enabled) return this

    val currentSelect by rememberUpdatedState(onSelect)
    val currentAutoScroll by rememberUpdatedState(onAutoScroll)

    // Continue selecting while the finger rests at an edge — the grid scrolls
    // and every thumbnail that slides under the finger is selected.
    LaunchedEffect(state) {
        snapshotFlow { state.dragging to state.pointer }.collect { (dragging, _) ->
            if (!dragging) return@collect
            while (state.dragging) {
                val point = state.pointer
                if (point.x.isNaN()) break
                val scroller = currentAutoScroll ?: break
                val height = state.containerSize.height.toFloat()
                if (height <= 0f) break
                val localY = point.y - state.containerOrigin.y
                val edge = (height * 0.1f).coerceAtLeast(24f)
                val step = when {
                    localY < edge -> -16f
                    localY > height - edge -> 16f
                    else -> 0f
                }
                if (step == 0f) break
                scroller(step)
                withFrameNanos { }
                state.hitTest(state.pointer)?.let {
                    if (state.markSelected(it)) currentSelect(it)
                }
            }
        }
    }

    return this
        .onGloballyPositioned {
            state.containerOrigin = it.positionInRoot()
            state.containerSize = it.size
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                // Only a drag that STARTS on a thumbnail becomes a selection
                // swipe — otherwise the grid keeps its normal scrolling.
                val startUri = state.hitTest(state.containerOrigin + down.position)
                if (startUri == null) {
                    // Nothing to select here — leave scrolling to the grid.
                    return@awaitEachGesture
                }

                var selecting = false
                state.beginGesture()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!selecting) {
                        if ((change.position - down.position).getDistance() >
                            viewConfiguration.touchSlop
                        ) {
                            selecting = true
                            state.dragging = true
                        }
                    }
                    if (selecting) {
                        // Driven by us now — stop the grid from scrolling.
                        change.consume()
                        state.pointer = state.containerOrigin + change.position
                        state.hitTest(state.pointer)?.let {
                            if (state.markSelected(it)) currentSelect(it)
                        }
                    }
                }
                state.dragging = false
                state.pointer = Offset.Unspecified
            }
        }
}