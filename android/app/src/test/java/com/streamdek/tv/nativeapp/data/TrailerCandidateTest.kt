package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Which of a title's videos the television will play.
 *
 * The rule that differs from the handset is the one worth pinning: a Short is never blown up
 * across a living-room screen, even when it is the only thing on offer.
 */
class TrailerCandidateTest {
  @Test
  fun `a short is never chosen while a real trailer is on offer`() {
    val chosen = pickBestTrailerCandidate(
      listOf(
        TrailerCandidate("short", "Wicked | Official Trailer #shorts", 58),
        TrailerCandidate("real", "Wicked | Trailer", 150),
      ),
    )
    assertEquals("real", chosen)
  }

  @Test
  fun `a television plays nothing rather than a short blown up across the wall`() {
    // Null sends the resolver on to the next client and then to the curated pick, which is where
    // a real trailer for this title is most likely to come from.
    val chosen = pickBestTrailerCandidate(listOf(TrailerCandidate("short", "Wicked | Clip", 45)))
    assertNull(chosen)
  }

  @Test
  fun `a lone full-length trailer is still chosen`() {
    val chosen = pickBestTrailerCandidate(listOf(TrailerCandidate("real", "Wicked | Official Trailer", 143)))
    assertEquals("real", chosen)
  }

  @Test
  fun `an unknown runtime is not treated as short-form`() {
    // Unknown is unknown. Punishing it would throw away trailers whose metadata could not be read.
    assertFalse(isShortFormTrailerCandidate("Wicked | Official Trailer", null))
    assertEquals("unknown", pickBestTrailerCandidate(listOf(TrailerCandidate("unknown", "Wicked | Official Trailer", null))))
  }

  @Test
  fun `calling itself a trailer does not promote a short above a real one`() {
    val chosen = pickBestTrailerCandidate(
      listOf(
        TrailerCandidate("short", "Wicked | Official Trailer", 30),
        TrailerCandidate("real", "Wicked | A Look Inside the Trailer", 130),
      ),
    )
    assertEquals("real", chosen)
  }

  @Test
  fun `the promotional run still loses to the trailer`() {
    val chosen = pickBestTrailerCandidate(
      listOf(
        TrailerCandidate("promo", "Dune: Part Two | Tickets on Sale Now", 30),
        TrailerCandidate("trailer", "Dune: Part Two | Official Trailer 3", 165),
      ),
    )
    assertEquals("trailer", chosen)
  }
}
