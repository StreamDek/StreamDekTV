package com.streamdek.tv.nativeapp.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PeerFileSelectionTest {
    @Test
    fun `season pack chooses requested episode rather than largest file`() {
        val selected = selectPeerVideoFile(
            listOf(
                PeerFileCandidate(0, "Reacher.S04E01.mkv", 900),
                PeerFileCandidate(1, "Reacher.S04E03.mkv", 800),
                PeerFileCandidate(2, "Reacher.S04E04.mkv", 1_200),
            ),
            null, "Reacher", 4, 3,
        )
        assertEquals(1, selected.index)
    }

    @Test
    fun `missing episode is rejected rather than substituted`() {
        assertThrows(IllegalStateException::class.java) {
            selectPeerVideoFile(
                listOf(PeerFileCandidate(0, "Preacher.S04E05.mkv", 1_200)),
                null, "Reacher", 4, 5,
            )
        }
    }

    @Test
    fun `movie ignores sample and non-video files`() {
        val selected = selectPeerVideoFile(
            listOf(
                PeerFileCandidate(0, "sample.mkv", 100),
                PeerFileCandidate(1, "Reacher.2026.mkv", 1_000),
                PeerFileCandidate(2, "poster.jpg", 5_000),
            ),
            null, "Reacher", null, null,
        )
        assertEquals(1, selected.index)
    }
}
