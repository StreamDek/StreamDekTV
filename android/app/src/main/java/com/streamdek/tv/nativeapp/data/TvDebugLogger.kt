package com.streamdek.tv.nativeapp.data

import android.util.Log

object TvDebugLogger {
    private const val PREFIX = "StreamDekTV"
    private const val MAX_ENTRIES = 120
    data class Entry(val timestampMs: Long, val level: String, val tag: String, val message: String)
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)

    @Synchronized private fun record(level: String, tag: String, message: String) {
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(Entry(System.currentTimeMillis(), level, tag, message.take(500)))
    }
    @Synchronized fun snapshot(limit: Int = 30): List<Entry> = entries.takeLast(limit.coerceIn(1, MAX_ENTRIES))
    @Synchronized fun clear() = entries.clear()

    fun d(tag: String, message: String) { record("D", tag, message); Log.d("$PREFIX:$tag", message) }
    fun i(tag: String, message: String) { record("I", tag, message); Log.i("$PREFIX:$tag", message) }
    fun w(tag: String, message: String, error: Throwable? = null) {
        record("W", tag, if (error == null) message else "$message: ${error.localizedMessage.orEmpty()}")
        if (error != null) Log.w("$PREFIX:$tag", message, error) else Log.w("$PREFIX:$tag", message)
    }
    fun e(tag: String, message: String, error: Throwable? = null) {
        record("E", tag, if (error == null) message else "$message: ${error.localizedMessage.orEmpty()}")
        if (error != null) Log.e("$PREFIX:$tag", message, error) else Log.e("$PREFIX:$tag", message)
    }
}