package com.streamdek.tv.nativeapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSettingsTest {
    @Test
    fun `stored and legacy speed keys resolve to the canonical modes`() {
        assertEquals(AnimationSpeed.Off, AnimationSpeed.fromKey("off"))
        assertEquals(AnimationSpeed.Fast, AnimationSpeed.fromKey("FAST"))
        assertEquals(AnimationSpeed.Standard, AnimationSpeed.fromKey("normal"))
        assertEquals(AnimationSpeed.Cinematic, AnimationSpeed.fromKey("slow"))
        assertEquals(AnimationSpeed.Standard, AnimationSpeed.fromKey("unknown"))
    }

    @Test
    fun `speed scales only semantic motion durations`() {
        assertEquals(63, MotionSettings(AnimationSpeed.Fast).scaled(MotionDuration.instant))
        assertEquals(250, MotionSettings(AnimationSpeed.Standard).scaled(MotionDuration.standard))
        assertEquals(551, MotionSettings(AnimationSpeed.Cinematic).scaled(MotionDuration.long))
    }

    @Test
    fun `reduced motion overrides speed but preserves a brief crossfade`() {
        val settings = MotionSettings(AnimationSpeed.Cinematic, systemReducedMotion = true)

        assertTrue(settings.motionless)
        assertTrue(settings.overriddenBySystem)
        assertEquals(0, settings.scaled(MotionDuration.long))
        assertEquals(0, settings.stagger())
        assertEquals(MotionDuration.motionlessCrossfade, settings.crossfade())
    }

    @Test
    fun `explicit off is not reported as a system override`() {
        val settings = MotionSettings(AnimationSpeed.Off, systemReducedMotion = true)

        assertTrue(settings.motionless)
        assertFalse(settings.overriddenBySystem)
    }
}
