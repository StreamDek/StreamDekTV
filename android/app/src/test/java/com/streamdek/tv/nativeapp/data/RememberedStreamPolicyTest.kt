package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RememberedStreamPolicyTest {
    @Test
    fun `stored source is used only when cloud setting is enabled`() {
        assertEquals("stored", effectiveRememberedStreamKey(null, "stored", rememberLastSource = true))
        assertNull(effectiveRememberedStreamKey(null, "stored", rememberLastSource = false))
    }

    @Test
    fun `explicit selection always wins`() {
        assertEquals("selected", effectiveRememberedStreamKey("selected", "stored", rememberLastSource = true))
        assertEquals("selected", effectiveRememberedStreamKey("selected", "stored", rememberLastSource = false))
    }
}