package com.streamdek.tv.nativeapp.ui

import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

/**
 * Keeps the focused item near a consistent leading anchor instead of snapping it
 * to the start edge, which makes D-pad navigation read as a continuous rail.
 */
suspend fun LazyListState.animateToAnchoredItem(
    focusedIndex: Int,
    itemCount: Int,
    leadingItems: Int = 1,
    scrollOffset: Int = 0,
) {
    if (itemCount <= 0) return

    val boundedIndex = focusedIndex.coerceIn(0, itemCount - 1)
    val targetFirstVisible = (boundedIndex - leadingItems).coerceAtLeast(0)
    if (
        firstVisibleItemIndex == targetFirstVisible &&
        firstVisibleItemScrollOffset == scrollOffset
    ) {
        return
    }

    // Never snap when the viewer presses quickly. A new focus move cancels this animation and
    // starts another from the current position, preserving momentum instead of teleporting.
    animateScrollToItem(targetFirstVisible, scrollOffset)
}
