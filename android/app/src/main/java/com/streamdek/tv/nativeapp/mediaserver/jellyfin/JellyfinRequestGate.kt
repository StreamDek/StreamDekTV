package com.streamdek.tv.nativeapp.mediaserver.jellyfin

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Shared by reads to one server, including Home, My Media and library grids. */
internal class JellyfinRequestGate(
    concurrency: Int = 3,
    private val spacingMs: Long = 150L,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val permits = Semaphore(concurrency)
    private val starts = Mutex()
    private var nextStart = 0L
    private val retryAt = AtomicLong(0L)
    private val rateLimits = AtomicInteger(0)

    suspend fun <T> read(request: suspend () -> Result<T>): Result<T> = permits.withPermit {
        var result: Result<T>
        var retries = 0
        do {
            val allowed = starts.withLock {
                // A 429 from another in-flight read can extend the wait while we are asleep.
                while (true) {
                    // Once the server repeatedly refuses reads, fail queued background work
                    // promptly instead of sending the entire library queue through the limit.
                    if (retries == 0 && rateLimits.get() >= 2 && now() < retryAt.get()) return@withLock false
                    val wait = maxOf(nextStart, retryAt.get()) - now()
                    if (wait <= 0L) break
                    delay(wait)
                }
                nextStart = now() + spacingMs
                true
            }
            if (!allowed) return@withPermit Result.failure(JellyfinClient.StatusException(429, retryAt.get() - now()))
            result = request()
            val error = result.exceptionOrNull() as? JellyfinClient.StatusException
            if (error?.code != 429) {
                if (result.isSuccess) rateLimits.set(0)
                break
            }
            val failures = rateLimits.incrementAndGet()
            val fallback = (5_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(60_000L)
            val until = now() + (error.retryAfterMs ?: fallback).coerceAtLeast(1L)
            retryAt.updateAndGet { maxOf(it, until) }
        } while (retries++ < 1)
        result
    }
}
