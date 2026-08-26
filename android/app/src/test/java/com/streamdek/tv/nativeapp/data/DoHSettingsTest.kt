package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoHSettingsTest {
    @Test fun `predefined providers use HTTPS RFC 8484 endpoints`() {
        StreamDekDoHProviders.filter { it.id != "custom" }.forEach { provider ->
            assertNull("${provider.label} should have a valid HTTPS endpoint", DoHSettings.validateEndpoint(provider.endpoint.orEmpty()))
        }
    }

    @Test fun `custom endpoint rejects non HTTPS and malformed values`() {
        assertEquals("DNS over HTTPS requires an HTTPS URL.", DoHSettings.validateEndpoint("http://resolver.example/dns-query"))
        assertEquals("Enter a valid URL.", DoHSettings.validateEndpoint("not a url"))
        assertNull(DoHSettings.validateEndpoint("https://resolver.example/dns-query"))
    }
}
