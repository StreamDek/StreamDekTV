package com.streamdek.tv.nativeapp.ui.plex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Media server rows that keep going.
 *
 * A row arrives with its first stretch of titles. As the highlight nears the end of it, the next
 * stretch is read from the server in the row's own order and added on, so a library row holds the
 * whole library and Recently Added goes back as far as the server does - the same as on the phone.
 *
 * Rows that are not a media server's are passed through untouched. What was read is kept against
 * the row's first stretch as it arrived, so a row built again with different titles starts over
 * rather than carrying on from a list it no longer matches.
 */
@Stable
internal class MediaServerRowGrowth(
    private val repository: StreamDekRepository,
    private val scope: CoroutineScope,
) {
    private val extra = mutableStateMapOf<String, List<MediaItem>>()
    /** How far into each row's order the server has been read. */
    private val cursors = HashMap<String, Int>()
    private val ended = HashSet<String>()
    private val loading = HashSet<String>()

    /** [rail] with whatever has been read beyond its first stretch. */
    fun extended(rail: HomeRail): HomeRail {
        val more = extra[slot(rail)] ?: return rail
        return rail.copy(items = rail.items + more)
    }

    /** Called as a card in [rail] takes the highlight; [index] is its position in the row as drawn. */
    fun onFocused(rail: HomeRail, index: Int) {
        val id = slot(rail)
        if (id in ended || id in loading || !repository.isPageableMediaServerRow(rail.id)) return
        val shown = rail.items.size + (extra[id]?.size ?: 0)
        if (index < shown - LOOKAHEAD) return
        loading += id
        scope.launch {
            try {
                // The first stretch's size is where the server's order carries on from. A title
                // read twice - a series with episodes added days apart - is dropped below.
                var start = cursors[id] ?: rail.items.size
                var pages = 0
                while (pages++ < MAX_PAGES_PER_STEP) {
                    val page = runCatching { repository.mediaServerRowPage(rail.id, start, PAGE_SIZE) }.getOrNull()
                    if (page == null) {
                        ended += id
                        break
                    }
                    start = page.nextStart
                    val known = (rail.items + extra[id].orEmpty()).mapTo(HashSet()) { it.id }
                    val fresh = page.items.filter { known.add(it.id) }
                    if (fresh.isNotEmpty()) extra[id] = extra[id].orEmpty() + fresh
                    if (page.end) {
                        ended += id
                        break
                    }
                    if (fresh.isNotEmpty()) break
                }
                cursors[id] = start
            } finally {
                loading -= id
            }
        }
    }

    private fun slot(rail: HomeRail): String = "${rail.id}#${rail.items.size}#${rail.items.firstOrNull()?.id}"

    private companion object {
        /** How close to the end of a row the highlight gets before the next stretch is read. */
        const val LOOKAHEAD = 8
        const val PAGE_SIZE = 40
        /** Stretches read in one go when every title in them was already shown. */
        const val MAX_PAGES_PER_STEP = 5
    }
}

@Composable
internal fun rememberMediaServerRowGrowth(repository: StreamDekRepository): MediaServerRowGrowth {
    val scope = rememberCoroutineScope()
    return remember(repository) { MediaServerRowGrowth(repository, scope) }
}
