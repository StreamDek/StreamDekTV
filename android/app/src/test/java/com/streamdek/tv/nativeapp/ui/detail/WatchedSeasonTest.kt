package com.streamdek.tv.nativeapp.ui.detail

import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedSeasonTest {
    @Test
    fun `season is watched only when all of its episodes are in series history`() {
        val history = setOf(
            "tv:42:s1:e1", "tv:42:s1:e2",
            "tv:42:s2:e1",
            "tv:99:s1:e1",
        )
        val episodeKeys = seriesWatchedEpisodeKeys(history, "42")
        val seasons = listOf(
            SeasonRef(1, "Season 1", episodeCount = 2),
            SeasonRef(2, "Season 2", episodeCount = 2),
        )

        assertEquals(setOf(1), watchedSeasonNumbers(seasons, emptyList(), episodeKeys))
    }

    @Test
    fun `loaded episode numbers take precedence over assumed sequential counts`() {
        val seasons = listOf(SeasonRef(0, "Specials", episodeCount = 2))
        val loaded = listOf(
            SeasonDetail(
                seasonNumber = 0,
                name = "Specials",
                episodes = listOf(
                    SeasonEpisode(1, 1, "First"),
                    SeasonEpisode(3, 3, "Third"),
                ),
            ),
        )

        assertEquals(setOf(0), watchedSeasonNumbers(seasons, loaded, setOf("s0:e1", "s0:e3")))
    }
}
