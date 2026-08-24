package com.streamdek.tv.nativeapp.data

/** Rejects login/error/HTML bodies that otherwise become selectable tracks with no rendered cues. */
internal fun subtitleTextHasTimedCues(text: String, extension: String): Boolean {
    val sample = text.take(256_000)
    return when (extension) {
        "vtt", "srt" -> Regex("""(?m)^\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3}""").containsMatchIn(sample)
        "ass" -> Regex("""(?mi)^Dialogue:\s*\d+,""").containsMatchIn(sample)
        "ttml" -> Regex("""(?is)<p\b[^>]*(?:begin|end|dur)=""").containsMatchIn(sample)
        else -> false
    }
}
