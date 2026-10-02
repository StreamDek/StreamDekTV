package com.streamdek.tv.nativeapp.mediaserver

import com.streamdek.tv.nativeapp.mediaserver.jellyfin.JellyfinClient
import com.streamdek.tv.nativeapp.mediaserver.jellyfin.JellyfinRequestGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class JellyfinRequestGateTest {
    @Test fun `library reads have bounded concurrency`() = runBlocking {
        val gate = JellyfinRequestGate(concurrency = 2, spacingMs = 0)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val results = (1..12).map { index -> async {
            gate.read {
                peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                delay(10)
                active.decrementAndGet()
                Result.success(index)
            }.getOrThrow()
        } }.awaitAll()
        assertEquals((1..12).toList(), results)
        assertEquals(2, peak.get())
    }

    @Test fun `rate limited read waits and retries once`() = runBlocking {
        val gate = JellyfinRequestGate(spacingMs = 0)
        var calls = 0
        val started = System.nanoTime()
        val result = gate.read {
            if (++calls == 1) Result.failure(JellyfinClient.StatusException(429, 40L))
            else Result.success("titles")
        }
        assertEquals("titles", result.getOrThrow())
        assertEquals(2, calls)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 40L)
    }

    @Test fun `repeated rate limits stop and other errors are not retried`() = runBlocking {
        for (status in listOf(429, 401, 404, 500)) {
            var calls = 0
            val result = JellyfinRequestGate(spacingMs = 0).read<String> {
                calls++
                Result.failure(JellyfinClient.StatusException(status, 1L))
            }
            assertTrue(result.isFailure)
            assertEquals(if (status == 429) 2 else 1, calls)
        }
    }

    @Test fun `persistent rate limiting stops queued library reads and recovers after cooldown`() = runBlocking {
        val gate = JellyfinRequestGate(concurrency = 1, spacingMs = 0)
        var calls = 0
        (1..20).map { async {
            gate.read<String> {
                calls++
                Result.failure(JellyfinClient.StatusException(429, 50L))
            }
        } }.awaitAll()
        assertEquals(2, calls)
        delay(60)
        assertEquals("recovered", gate.read { Result.success("recovered") }.getOrThrow())
    }
}
