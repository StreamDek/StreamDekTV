package com.streamdek.tv.nativeapp.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerStageTest {
    @Test
    fun `wide cinema trailer is letterboxed instead of enlarged and cropped`() {
        val (scaleX, scaleY) = trailerFitScale(16f / 9f, 2.39f)
        assertEquals(1f, scaleX, 0.0001f)
        assertEquals((16f / 9f) / 2.39f, scaleY, 0.0001f)
    }

    @Test
    fun `narrow trailer is pillarboxed instead of stretched`() {
        val (scaleX, scaleY) = trailerFitScale(16f / 9f, 4f / 3f)
        assertEquals((4f / 3f) / (16f / 9f), scaleX, 0.0001f)
        assertEquals(1f, scaleY, 0.0001f)
    }
}
