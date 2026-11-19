package com.rawr.camera.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt

/**
 * Long-press-and-drag reordering for a row of equal slots (the preset cards, the mode names; Nov 19).
 *
 * The finger is followed in screen coordinates. A card's own coordinates move under the finger when it is
 * translated and again when it changes place in the row, and drag amounts measured in them made the card jump
 * back and forth (the preset strip "glitched" when a card was moved). The card's place is worked out from where
 * the finger has taken it, so it never moves twice for one step.
 */
internal class RowReorder<K : Any>(private val stepPx: Float, private val minIndex: Int = 0) {
    var dragging by mutableStateOf<K?>(null)
        private set
    private var finger by mutableFloatStateOf(0f)
    private var startFinger = 0f
    private var startSlot = 0f
    private var firstSlot = 0f
    private val slots = HashMap<K, LayoutCoordinates>()

    private fun slotX(key: K): Float? = slots[key]?.takeIf { it.isAttached }?.positionInRoot()?.x

    /** How far [key]'s card is drawn from its slot: where the finger has taken it (0 unless it is dragged). */
    fun translation(key: K): Float {
        if (dragging != key) return 0f
        val now = slotX(key) ?: return 0f
        return startSlot + (finger - startFinger) - now
    }

    /**
     * The slot's layout and its long-press drag. Put it before the card's graphicsLayer (so the translation doesn't
     * move the coordinates the finger is read in). [onMove] gets the place the card should take now.
     */
    fun Modifier.reorderSlot(
        key: K,
        enabled: Boolean,
        order: SnapshotStateList<K>,
        onStart: () -> Unit,
        onMove: (from: Int, to: Int) -> Unit,
        onEnd: () -> Unit,
        onCancel: () -> Unit
    ): Modifier = onGloballyPositioned { slots[key] = it }.then(
        if (!enabled) Modifier else Modifier.pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    val c = slots[key]
                    if (c != null && c.isAttached) {
                        startSlot = c.positionInRoot().x
                        startFinger = c.localToRoot(Offset(at.x, 0f)).x
                        finger = startFinger
                        firstSlot = startSlot - order.indexOf(key) * stepPx
                        dragging = key
                        onStart()
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val c = slots[key]
                    if (dragging == key && c != null && c.isAttached) {
                        finger = c.localToRoot(Offset(change.position.x, 0f)).x
                        val visual = startSlot + (finger - startFinger)
                        val to = ((visual - firstSlot) / stepPx).roundToInt().coerceIn(minIndex, order.lastIndex)
                        val from = order.indexOf(key)
                        if (from >= minIndex && to != from) onMove(from, to)
                    }
                },
                onDragEnd = {
                    if (dragging == key) {
                        dragging = null
                        onEnd()
                    }
                },
                onDragCancel = {
                    if (dragging == key) {
                        dragging = null
                        onCancel()
                    }
                }
            )
        }
    )
}
