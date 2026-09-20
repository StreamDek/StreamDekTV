package com.streamdek.tv.nativeapp.ui.player

import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.Languages

/** Match content metadata, never IDs assigned independently by each playback engine. */
internal fun matchingEngineTrack(wanted: MpvTrackInfo, tracks: List<MpvTrackInfo>): MpvTrackInfo? {
    val language = Languages.normalize(wanted.language.orEmpty())
    val candidates = tracks.filter { Languages.normalize(it.language.orEmpty()) == language }
    return candidates.firstOrNull { it.title.orEmpty().trim().equals(wanted.title.orEmpty().trim(), ignoreCase = true) }
        ?: candidates.singleOrNull()
}
