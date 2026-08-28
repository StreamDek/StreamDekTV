package com.streamdek.tv.nativeapp.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * Shares one in-flight call between everyone who asks for the same thing at the same time.
 *
 * Screens ask for the same resource from several places at once, and not always with the same
 * timing: the player's subtitle lookup, for instance, is keyed on both the source URL and the
 * media's IMDb id, so it fires once when the URL arrives and again when the detail record lands a
 * few milliseconds later — two identical fan-outs across every subtitle provider, measured
 * returning the same 57 results twice while the decoder was starting on a CPU-saturated stick.
 *
 * A plain cache does not fix that, because the second caller arrives while the first is still in
 * flight and so misses. This keys on the request instead: the second caller waits on the first
 * caller's result.
 *
 * Deliberately not a cache. Nothing is retained after the call completes, so this changes how many
 * times work happens concurrently and nothing about how fresh the answer is; a caller that wants
 * results retained still needs its own cache with its own TTL.
 */
class RequestCoalescer<K : Any, V>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    /**
     * Runs [block] for [key], or joins the call already running for it.
     *
     * The entry is removed as soon as the call settles — including when it fails, so a failure is
     * not remembered and the next caller genuinely retries rather than being handed the same
     * exception forever.
     */
    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            inFlight[key]?.let { existing ->
                // A deferred that completed between the lookup and the await is not a valid join
                // target; fall through and start a fresh one.
                if (existing.isActive) return existing.await()
            }
            val started = CompletableDeferred<Unit>()
            val deferred = scope.async {
                started.await()
                block()
            }
            if (inFlight.putIfAbsent(key, deferred) != null) {
                deferred.cancel()
                continue
            }
            started.complete(Unit)
            return try {
                deferred.await()
            } finally {
                inFlight.remove(key, deferred)
            }
        }
    }
}
