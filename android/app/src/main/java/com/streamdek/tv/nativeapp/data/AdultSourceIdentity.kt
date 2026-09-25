package com.streamdek.tv.nativeapp.data

/** Bounded identity normalisation shared by Mobile and TV. No URLs leave the device. */
internal object AdultSourceIdentity {
  private val marks = Regex("[\\p{M}\\p{Cf}]")
  private val gaps = Regex("[^\\p{L}\\p{N}]+")
  private val entities = Regex("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});")
  private val namedEntities = Regex("&(?:amp|nbsp|quot|apos|lt|gt);", RegexOption.IGNORE_CASE)
  private val aliases = setOf("pornmz", "xprimehub", "yespornplease", "perverzija", "brazzers", "pornhd", "eporner", "porntrex",
    "pornhub", "xvideos", "xhamster", "youporn", "redtube", "spankbang", "xnxx", "chaturbate", "camsoda", "stripchat", "livejasmin", "bangbros")
    .flatMap { listOf(it, it + "provider", it + "plugin") }.map(::collapseRepeats).toSet()
  private val prefixes = aliases.flatMap { id -> (1..id.length).map { id.take(it) } }.toSet()
  private val confusables = mapOf('а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'у' to 'y', 'і' to 'i', 'ј' to 'j',
    'α' to 'a', 'ε' to 'e', 'ο' to 'o', 'ρ' to 'p', 'χ' to 'x')
  /**
   * Normalised forms already worked out.
   *
   * Every catalogue card's title, id and source names come through here, often several times over
   * (once per rule and per term), and the same strings come round again on every Home refresh. Each
   * one is a handful of Unicode passes, which on a Fire TV stick added up to seconds of main-thread
   * time per Home load. The result depends on nothing but the input, so it is safe to keep.
   */
  private val normalized = object : LinkedHashMap<String, String>(512, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4096
  }

  fun normalize(raw: String): String {
    synchronized(normalized) { normalized[raw] }?.let { return it }
    val result = normalizeUncached(raw)
    synchronized(normalized) { normalized[raw] = result }
    return result
  }

  private fun normalizeUncached(raw: String): String {
    var value = raw.take(4096)
    // Each step below is skipped only where it cannot change the text: decoding needs a '%',
    // entities need a '&', and plain ASCII has nothing for NFKD, the mark strip or the confusable
    // map to act on. Most titles are plain ASCII, and those regex passes were the cost here.
    repeat(2) {
      if (value.indexOf('%') >= 0) {
        value = runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
      }
    }
    if (value.indexOf('&') >= 0) {
      value = entities.replace(value) { match ->
        val code = match.groupValues[1]
        val n = if (code.startsWith("x", true)) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
        if (n != null && n in 0..0x10ffff) String(Character.toChars(n)) else " "
      }
      value = value.replace(namedEntities, " ")
    }
    if (value.all { it.code < 0x80 }) return asciiGapsToSpaces(value.lowercase(java.util.Locale.ROOT))
    value = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).replace(marks, "").lowercase(java.util.Locale.ROOT)
    val mapped = StringBuilder(value.length)
    for (c in value) mapped.append(confusables[c] ?: c)
    return mapped.toString().replace(gaps, " ").trim()
  }

  /** [gaps] for text already known to be lowercase ASCII: each run of non-alphanumerics becomes one space, none at the ends. */
  private fun asciiGapsToSpaces(value: String): String {
    val out = StringBuilder(value.length)
    var gap = false
    for (c in value) {
      if (c in 'a'..'z' || c in '0'..'9') {
        if (gap && out.isNotEmpty()) out.append(' ')
        gap = false
        out.append(c)
      } else {
        gap = true
      }
    }
    return out.toString()
  }

  /** Collapses each run of one repeated letter a-z to a single letter, as `([a-z])\1+` -> `$1` did. */
  private fun collapseRepeats(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) {
      if (c in 'a'..'z' && out.isNotEmpty() && out[out.length - 1] == c) continue
      out.append(c)
    }
    return out.toString()
  }
  private fun skeleton(value: String) = value.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('7', 't')
  fun matches(raw: String): Boolean {
    if (adultRepository(raw)) return true
    val words = normalize(raw).split(' ').take(256)
    for (i in words.indices) {
      var joined = ""
      for (j in i until minOf(words.size, i + 16)) {
        joined = collapseRepeats(joined + skeleton(words[j]))
        if (joined !in prefixes) break
        if (joined in aliases) return true
      }
    }
    return false
  }
  fun adultRepository(raw: String): Boolean = raw.startsWith("http", ignoreCase = true) && runCatching {
    val uri = java.net.URI(raw)
    if (uri.host?.lowercase(java.util.Locale.ROOT) !in setOf("github.com", "raw.githubusercontent.com")) false
    else uri.path.lowercase(java.util.Locale.ROOT).split('/').filter { it.isNotBlank() }.take(2).joinToString("/") in
      setOf("phisher98/cxxx", "owenconnorz/xxx", "punpunsx/cloudstream-18plus-extensions", "kraptor123/cs-gizlikeyif")
  }.getOrDefault(false)
}
