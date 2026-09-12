package com.streamdek.tv.nativeapp.ui.network

import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PagedRailResponse

internal data class NetworkCatalogPages(
    val items: List<MediaItem> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
) {
    val hasMore get() = page < totalPages
    fun append(next: PagedRailResponse) = NetworkCatalogPages(
        (items + next.results).distinctBy { "${it.type}:${it.id}" },
        next.page,
        if (next.page > page) next.total_pages else next.page,
    )
}
