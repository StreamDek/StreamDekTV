package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesResumePolicyTest {
    private val episodes = (1..3).map { SeriesEpisodeSlot(2, it) } + SeriesEpisodeSlot(3, 1)

    @Test fun `partial episode is selected with its exact position`() {
        val state = getSeriesResumeState(episodes, listOf(SeriesProgressEvent(2, 3, 1122.0, 37.4, updatedAtMillis = 10)))
        assertEquals(SeriesEpisodeSlot(2, 3), state.target)
        assertEquals(1122.0, state.resumePositionSec ?: 0.0, 0.001)
    }

    @Test fun `next episode follows the highest completed episode`() {
        val state = getSeriesResumeState(episodes, emptyList(), setOf("s2:e1", "s2:e2"))
        assertEquals(SeriesEpisodeSlot(2, 3), state.target)
    }

    @Test fun `explicit unwatched event reselects that episode`() {
        val state = getSeriesResumeState(
            episodes,
            listOf(SeriesProgressEvent(2, 2, status = "unwatched", updatedAtMillis = 20)),
            setOf("s2:e1", "s2:e2"),
        )
        assertEquals(SeriesEpisodeSlot(2, 2), state.target)
        assertEquals(false, "s2:e2" in state.watchedEpisodeKeys)
    }

    @Test fun `completed season advances into next season`() {
        val state = getSeriesResumeState(episodes, emptyList(), setOf("s2:e1", "s2:e2", "s2:e3"))
        assertEquals(SeriesEpisodeSlot(3, 1), state.target)
    }
}
