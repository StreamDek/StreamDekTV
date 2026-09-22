package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import com.google.gson.JsonParseException
import org.junit.Assert.*
import org.junit.Test

class MediaItemJsonTest {
    private val gson = Gson()

    @Test fun missingAndNullHeadersCanBeCopied() {
        for (extra in listOf("", ",\"requestHeaders\":null")) {
            val item = gson.fromJson("""{"id":"1","title":"Title","type":"movie"$extra}""", MediaItem::class.java)
            assertEquals(emptyMap<String, String>(), item.copy(title = "Changed").requestHeaders)
        }
    }

    @Test fun nestedItemsUseTheSameBoundary() {
        val envelope = gson.fromJson("""{"items":[{"id":"1","title":"Live","mediaType":"live"}]}""", LiveFavouriteChannelsEnvelope::class.java)
        assertEquals("live", envelope.items.single().copy().type)
        assertTrue(envelope.items.single().requestHeaders.isEmpty())
    }

    @Test fun headersAndDrmSurviveRoundTrip() {
        val original = MediaItem("1", title = "Live", type = "live", requestHeaders = mapOf("Referer" to "example"), drmLicenseType = "clearkey", drmClearKeys = mapOf("fixture" to "value"))
        assertEquals(original, gson.fromJson(gson.toJson(original), MediaItem::class.java))
    }

    @Test fun missingRequiredIdentityFailsAtDecode() {
        assertThrows(JsonParseException::class.java) {
            gson.fromJson("""{"title":"Title","type":"movie"}""", MediaItem::class.java)
        }
    }
}
