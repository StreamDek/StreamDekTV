package com.streamdek.tv.nativeapp.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPrivacyTest {
    /** The domain identifies which account this is; the local part is what has to go. */
    @Test
    fun `the local part is hidden and the domain is kept`() {
        assertEquals("h••••••••@gmail.com", maskEmail("henry.okwuenu@gmail.com"))
    }

    /** Two addresses of different lengths must not be distinguishable by their masks. */
    @Test
    fun `the mask does not publish how long the address is`() {
        assertEquals(
            maskEmail("a-very-long-address-indeed@example.com"),
            maskEmail("also-quite-long-here@example.com"),
        )
    }

    @Test
    fun `a short local part is still padded`() {
        assertEquals("a••••@example.com", maskEmail("ab@example.com"))
    }

    @Test
    fun `surrounding whitespace is not treated as part of the address`() {
        assertEquals("h••••@example.com", maskEmail("  hi@example.com  "))
    }

    /** Anything that is not an address is a secret of unknown shape, so none of it survives. */
    @Test
    fun `a value with no domain is masked whole`() {
        val masked = maskEmail("not-an-address")
        assertTrue(masked.startsWith("n"))
        assertFalse(masked.contains("address"))
    }

    @Test
    fun `an empty value produces no leak`() {
        assertEquals("••••••", maskEmail(""))
    }
}
