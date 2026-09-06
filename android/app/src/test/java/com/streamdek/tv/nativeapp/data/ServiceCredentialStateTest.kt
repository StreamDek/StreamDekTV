package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The content-service credential model on the television.
 *
 * The vault itself is keystore-backed and belongs in an instrumented test. What is worth pinning
 * here is that the television describes a credential exactly as the phone does — the same summary
 * for the same state — because the moment those drift, a household with both sees one device
 * claiming a service is connected while the other says it is not.
 */
class ServiceCredentialStateTest {

    @Test
    fun `a masked key reveals four characters and no more`() {
        val masked = maskServiceKey("abcdef0123456789")
        assertEquals("••••••••6789", masked)
        assertFalse(masked.contains("abcdef"))
    }

    @Test
    fun `service ids round-trip and nothing else is a content service`() {
        assertEquals(ContentService.Tmdb, ContentService.fromId("tmdb"))
        assertEquals(ContentService.Mdblist, ContentService.fromId("MDBLIST"))
        assertNull(ContentService.fromId("simkl"))
        assertNull(ContentService.fromId(null))
    }

    @Test
    fun `the summary distinguishes an inherited key from one typed on this television`() {
        val fromAccount = ContentServiceState(
            ContentService.Tmdb,
            status = CredentialStatus.Connected,
            storage = CredentialStorage.Account,
        )
        val fromThisTv = ContentServiceState(
            ContentService.Tmdb,
            status = CredentialStatus.Connected,
            storage = CredentialStorage.Device,
        )

        // The difference is the whole point of the feature on a television: one of these required
        // no typing, and the viewer should be able to see which. The summary is a resource id now,
        // so what this pins down is that the two states do not share one - asserting on the English
        // would be asserting on one locale of eight, and check-translations.mjs covers the rest.
        assertNotEquals(0, fromAccount.summaryRes)
        assertNotEquals(0, fromThisTv.summaryRes)
        assertNotEquals(fromAccount.summaryRes, fromThisTv.summaryRes)
    }

    @Test
    fun `a key the service has started refusing outranks the connection state`() {
        val stale = ContentServiceState(
            ContentService.Mdblist,
            status = CredentialStatus.NeedsAttention,
            storage = CredentialStorage.Account,
        )
        assertNotEquals(ContentServiceState(ContentService.Mdblist).summaryRes, stale.summaryRes)
        // Still configured: the viewer needs Update rather than Add, and the explanation with it.
        assertTrue(stale.configured)
    }

    @Test
    fun `an unconfigured service says so plainly`() {
        assertNotEquals(0, ContentServiceState(ContentService.Tmdb).summaryRes)
        assertFalse(ContentServiceState(ContentService.Tmdb).configured)
    }

    @Test
    fun `the two services are configured independently`() {
        val state = ContentServicesState()
            .with(ContentServiceState(ContentService.Mdblist, status = CredentialStatus.Connected))
        assertTrue(state.mdblist.configured)
        assertFalse("configuring MDBList must not imply TMDB", state.tmdb.configured)
        assertTrue(state.anyConfigured)
    }

    @Test
    fun `each storage choice carries its own wording`() {
        // The wording moved into string resources, so this can no longer read the sentences - a
        // plain JVM test has no resources to resolve them against, and asserting on English would
        // only be asserting on one locale of eight. What is still worth pinning down here is that
        // the two choices are distinguishable and neither is left without a label or an
        // explanation; that the sentences themselves exist in every language is checked by
        // scripts/check-translations.mjs.
        for (storage in CredentialStorage.entries) {
            assertNotEquals(0, storage.labelRes)
            assertNotEquals(0, storage.detailRes)
        }
        assertNotEquals(CredentialStorage.Account.labelRes, CredentialStorage.Device.labelRes)
        assertNotEquals(CredentialStorage.Account.detailRes, CredentialStorage.Device.detailRes)
    }

    @Test
    fun `a refused key and an unreachable service carry different messages`() {
        assertEquals(CredentialFailure.InvalidKey, CredentialFailure.fromId("invalid_key"))
        assertEquals(CredentialFailure.ServiceUnavailable, CredentialFailure.fromId("service_unavailable"))
        assertEquals(CredentialFailure.Malformed, CredentialFailure.fromId("malformed"))
    }

    @Test
    fun `every failure has a sentence of its own`() {
        // What a viewer must never be shown is a status code, and what they must always be shown is
        // something. The sentences live in the resources now, so this checks each failure carries a
        // distinct one rather than reading the English of any single locale.
        val ids = CredentialFailure.entries.map { it.messageRes }
        ids.forEach { assertNotEquals(0, it) }
        assertEquals("each failure needs its own wording", ids.size, ids.toSet().size)
    }

    @Test
    fun `the exception a screen catches names the failure rather than carrying English`() {
        val thrown = CredentialFailureException(CredentialFailure.ServiceUnavailable)
        assertEquals(CredentialFailure.ServiceUnavailable, thrown.failure)
        // The message is the constant's name, for a log. Nothing on screen comes from it.
        assertFalse("a viewer must never be shown a status code", Regex("\\b[45]\\d\\d\\b").containsMatchIn(thrown.message.orEmpty()))
    }

    @Test
    fun `every service can explain itself without leaving the television`() {
        ContentService.all.forEach { service ->
            assertNotEquals(0, service.taglineRes)
            assertNotEquals(0, service.blurbRes)
            assertTrue(service.usesRes.isNotEmpty())
            // A television cannot usefully open a link, so the steps have to stand on their own
            // and the address has to be readable and typeable from the sofa.
            assertTrue(service.howToGetRes.isNotEmpty())
            assertNotEquals(0, service.keyHintRes)
            assertFalse("the TV shows this as text, not a link", service.keyUrl.startsWith("http"))
        }
    }
}
