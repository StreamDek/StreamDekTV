package com.streamdek.tv.nativeapp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * One event as the backend's ingest endpoint expects it.
 *
 * Nulls are omitted by Gson, and the backend treats a missing field as absent rather than empty,
 * so an unknown dimension stays unknown instead of becoming a bucket of its own.
 */
data class TelemetryEventPayload(
    val type: String,
    val occurredAt: String,
    val correlationId: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null,
    val mediaTitle: String? = null,
    val addonKey: String? = null,
    val provider: String? = null,
    val outcome: String? = null,
    val errorCategory: String? = null,
    val errorCode: String? = null,
    val durationMs: Long? = null,
    val resultCount: Int? = null,
    val metadata: Map<String, Any?>? = null,
)

private data class TelemetryBatch(val events: List<TelemetryEventPayload>)

private data class TelemetryAck(val accepted: Int = 0, val rejected: Int = 0)

/**
 * Client-side funnel telemetry for the TV app.
 *
 * The backend sees which add-ons it queried and which debrid providers it tried; only the device
 * knows whether a stream ever actually played. Without these events the console can report a
 * successful resolve and still not say whether the viewer saw anything.
 *
 * Constraints, in priority order:
 *
 *  - Never affect playback. Everything is off the calling thread, the queue is bounded, and every
 *    failure is swallowed. Losing telemetry is fine; stalling a stream is not.
 *  - Never report what it does not know. A source that failed and was replaced by a working one
 *    is not a failed playback and is not recorded as one.
 *  - Carry no content beyond the media id and title the catalogue already exposes — no stream
 *    URLs, no magnets, no credentials.
 */
object Telemetry {

    // Must match the taxonomy the backend accepts; anything else is rejected there rather than
    // silently counted, so these are constants rather than inline strings.
    const val CONTENT_OPENED = "content_opened"
    const val SEARCH_PERFORMED = "search_performed"
    const val PLAYBACK_STARTED = "playback_started"
    const val PLAYBACK_FAILED = "playback_failed"
    const val SESSION_STARTED = "session_started"

    const val CATEGORY_PLAYBACK = "playback"
    const val CATEGORY_RESOLVER = "resolver"
    const val CATEGORY_TIMEOUT = "timeout"
    const val CATEGORY_NETWORK = "network"
    const val CATEGORY_UNKNOWN = "unknown"

    private const val MAX_QUEUE = 200
    private const val FLUSH_AT = 20
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val MAX_BATCH = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val queue = ArrayDeque<TelemetryEventPayload>()

    @Volatile private var api: StreamDekApi? = null
    private var flushJob: Job? = null
    @Volatile private var enabled: Boolean = true

    fun configure(client: StreamDekApi) {
        api = client
        if (flushJob == null) {
            flushJob = scope.launch {
                while (true) {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) scope.launch { runCatching { mutex.withLock { queue.clear() } } }
    }

    /**
     * A new correlation id for one playback attempt.
     *
     * The same id goes out as `x-correlation-id` on the stream request the attempt triggers, which
     * is what lets the console line this device's outcome up against the add-on calls and debrid
     * resolves the backend made for it. One id per viewer action, not per retried source.
     */
    fun newCorrelationId(): String = "tv_${UUID.randomUUID().toString().replace("-", "").take(20)}"

    // ── Emission ────────────────────────────────────────────────────────────

    fun contentOpened(mediaId: String?, mediaType: String?, title: String?) {
        track(
            TelemetryEventPayload(
                type = CONTENT_OPENED,
                occurredAt = now(),
                mediaId = mediaId,
                mediaType = normaliseMediaType(mediaType),
                mediaTitle = title,
            ),
        )
    }

    /**
     * A search and how many results it produced. The query text is deliberately not sent — it is
     * free text a viewer may type anything into, and none of it is needed to answer "are searches
     * coming back empty".
     */
    fun searchPerformed(resultCount: Int) {
        track(
            TelemetryEventPayload(
                type = SEARCH_PERFORMED,
                occurredAt = now(),
                resultCount = resultCount.coerceAtLeast(0),
                outcome = if (resultCount > 0) "success" else "empty",
            ),
        )
    }

    fun playbackStarted(
        correlationId: String?,
        mediaId: String?,
        mediaType: String?,
        title: String?,
        addonKey: String?,
        provider: String?,
        durationMs: Long?,
        sourcesTried: Int,
    ) {
        track(
            TelemetryEventPayload(
                type = PLAYBACK_STARTED,
                occurredAt = now(),
                correlationId = correlationId,
                mediaId = mediaId,
                mediaType = normaliseMediaType(mediaType),
                mediaTitle = title,
                addonKey = addonKey,
                provider = provider,
                outcome = "success",
                durationMs = durationMs?.coerceAtLeast(0L),
                // How many ranked sources were burned before one played. A rising value is a
                // resolver problem the success rate on its own would hide.
                metadata = mapOf("sourcesTried" to sourcesTried),
            ),
        )
    }

    /**
     * A playback attempt the viewer actually saw fail.
     *
     * Emitted only once the app has stopped rolling on to other sources. Recording it earlier
     * would count a source that was successfully replaced as a failed playback, which would make
     * the success rate meaningless while the app was doing exactly what it should.
     */
    fun playbackFailed(
        correlationId: String?,
        mediaId: String?,
        mediaType: String?,
        title: String?,
        addonKey: String?,
        provider: String?,
        errorCategory: String,
        errorCode: String?,
        durationMs: Long?,
        sourcesTried: Int,
    ) {
        track(
            TelemetryEventPayload(
                type = PLAYBACK_FAILED,
                occurredAt = now(),
                correlationId = correlationId,
                mediaId = mediaId,
                mediaType = normaliseMediaType(mediaType),
                mediaTitle = title,
                addonKey = addonKey,
                provider = provider,
                outcome = "failure",
                errorCategory = errorCategory,
                errorCode = errorCode,
                durationMs = durationMs?.coerceAtLeast(0L),
                metadata = mapOf("sourcesTried" to sourcesTried),
            ),
        )
    }

    fun sessionStarted() {
        track(TelemetryEventPayload(type = SESSION_STARTED, occurredAt = now()))
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun track(event: TelemetryEventPayload) {
        if (!enabled || api == null) return

        scope.launch {
            runCatching {
                val shouldFlush = mutex.withLock {
                    // Bounded: a device that cannot reach the backend drops the oldest events
                    // rather than growing until the process is killed.
                    if (queue.size >= MAX_QUEUE) queue.removeFirstOrNull()
                    queue.addLast(event)
                    queue.size >= FLUSH_AT
                }
                if (shouldFlush) flush()
            }
        }
    }

    /** Sends whatever is queued. Safe to call from anywhere; never throws. */
    fun flush() {
        val client = api ?: return
        if (!enabled) return

        scope.launch {
            runCatching {
                val batch = mutex.withLock {
                    if (queue.isEmpty()) return@launch
                    val take = minOf(queue.size, MAX_BATCH)
                    val items = ArrayList<TelemetryEventPayload>(take)
                    repeat(take) { queue.removeFirstOrNull()?.let(items::add) }
                    items
                }
                if (batch.isEmpty()) return@launch

                val sent = runCatching {
                    client.post<TelemetryAck>("/telemetry/events", TelemetryBatch(batch))
                }.isSuccess

                if (!sent) {
                    // Put them back at the front so ordering survives a transient outage, but only
                    // up to the cap — a permanently unreachable backend must not accumulate.
                    mutex.withLock {
                        batch.asReversed().forEach { event ->
                            if (queue.size < MAX_QUEUE) queue.addFirst(event)
                        }
                    }
                }
            }
        }
    }

    private fun now(): String = Instant.now().toString()

    /** The catalogue says `series`; the backend's analytics group on `movie`/`series`. */
    private fun normaliseMediaType(value: String?): String? = when (value?.lowercase()) {
        null, "" -> null
        "tv", "show", "series" -> "series"
        "movie", "film" -> "movie"
        else -> value.lowercase()
    }
}

/**
 * Maps a playback failure message onto the backend's error taxonomy.
 *
 * Conservative on purpose: a wrong category sends an operator looking in the wrong place, so
 * anything not clearly recognisable is reported as `unknown` rather than guessed at.
 */
fun classifyPlaybackFailure(message: String?): String {
    val text = message?.lowercase().orEmpty()
    return when {
        text.isBlank() -> Telemetry.CATEGORY_UNKNOWN
        "timeout" in text || "timed out" in text -> Telemetry.CATEGORY_TIMEOUT
        "unable to resolve host" in text || "failed to connect" in text ||
            "network" in text || "unreachable" in text -> Telemetry.CATEGORY_NETWORK
        "no seeders" in text || "not cached" in text || "debrid" in text ||
            "resolve" in text -> Telemetry.CATEGORY_RESOLVER
        else -> Telemetry.CATEGORY_PLAYBACK
    }
}
