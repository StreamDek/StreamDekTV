package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffAlgorithmTest {
    @Test
    fun fireTvEnvelopeUsesAndroidKeystoreCompatibleMgf1Digest() {
        assertEquals("SHA-1", handoffMgf1Digest("RSA-OAEP-256-MGF1-SHA1+A256GCM").digestAlgorithm)
    }

    @Test
    fun legacyEnvelopeRetainsItsOriginalDigest() {
        assertEquals("SHA-256", handoffMgf1Digest("RSA-OAEP-256+A256GCM").digestAlgorithm)
    }
}
