package com.streamdek.tv.nativeapp.data
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
class MediaClassificationTest {
  @Test fun `common aliases normalize but original Stremio tv context stays distinguishable`() {
    for (alias in listOf("series", "tv", "tvshow", "tv_series", "show", " TV Show ", "television-series"))
      assertEquals("tv", MediaClassification.canonical(alias))
    for (alias in listOf("movie", "movies", "film", "films", "Feature Film", "tv_movie"))
      assertEquals("movie", MediaClassification.canonical(alias))
    assertEquals("tv", MediaClassification.item("tv", "other", "opaque"))
    assertEquals("tv", MediaClassification.item("tv", "tv", "opaque", episodic = true))
    assertEquals("tv", MediaClassification.item("tv", "tv", "tmdb:tv:1399"))
    assertEquals("live", MediaClassification.item("tv", "tv", "channel"))
    assertEquals("unknown", MediaClassification.item(null, null, "opaque"))
  }

  @Test fun `mixed catalogs classify individual titles and native tv stays live`() {
    assertEquals("unknown", mapAddonCatalogType("other"))
    assertEquals("unknown", mapAddonCatalogType("anime"))
    assertEquals("tv", MediaClassification.item("series", "other", "cnc:1"))
    assertEquals("movie", MediaClassification.item("film", "other", "cnc:2"))
    assertEquals("live", MediaClassification.item("tv", "tv", "cnc:3"))
    assertEquals("tv", MediaClassification.item(null, "other", "tmdb:tv:1399"))
    assertEquals("unknown", MediaClassification.item(null, "other", "cnc:4"))
  }
  @Test fun `home search watchlist and continue navigation retain original provider identity`() {
    val item = MediaItem(id="cnc:%2F:opaque",tmdbId=1399,type="tv",title="Title",sourceAddonId="addon",sourceCatalogType="other")
    val restored = Gson().fromJson(Gson().toJson(item), MediaItem::class.java)
    assertEquals(AddonMediaReference("addon","other",item.id), AddonMediaReference.decode(restored.detailLookupId()))
    assertEquals("1399", MediaItem(id="1399",type="tv",title="Title").detailLookupId())
  }
  @Test fun `provider ids are never mined for imdb or tmdb substrings`() {
    for (id in listOf("cnc:tt1234567", "cs:1399", "1399")) assertNull(MediaClassification.enrichmentId(id,true))
    assertEquals("tmdb:tv:1399",MediaClassification.enrichmentId("tmdb:tv:1399",true))
  }
}
