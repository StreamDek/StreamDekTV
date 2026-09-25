package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AdultSourceIdentity.normalize] and [AdultSourceIdentity.matches] skip their regex passes where
 * those passes cannot change the text, and remember their answers. Both are performance changes
 * only, so this holds them to the plain implementation they replaced, on generated input that
 * leans on the cases the shortcuts are about: ASCII, '%', '&', entities, repeats and non-ASCII.
 */
class AdultSourceIdentityEquivalenceTest {
  private object Reference {
    val marks = Regex("[\\p{M}\\p{Cf}]")
    val gaps = Regex("[^\\p{L}\\p{N}]+")
    val entities = Regex("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});")
    val repeatedLetters = Regex("([a-z])\\1+")
    val confusables = mapOf('а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'у' to 'y', 'і' to 'i', 'ј' to 'j',
      'α' to 'a', 'ε' to 'e', 'ο' to 'o', 'ρ' to 'p', 'χ' to 'x')
    val aliases = setOf("pornmz", "xprimehub", "yespornplease", "perverzija", "brazzers", "pornhd", "eporner", "porntrex",
      "pornhub", "xvideos", "xhamster", "youporn", "redtube", "spankbang", "xnxx", "chaturbate", "camsoda", "stripchat", "livejasmin", "bangbros")
      .flatMap { listOf(it, it + "provider", it + "plugin") }.map { it.replace(repeatedLetters, "$1") }.toSet()
    val prefixes = aliases.flatMap { id -> (1..id.length).map { id.take(it) } }.toSet()

    fun normalize(raw: String): String {
      var value = raw.take(4096)
      repeat(2) { value = runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value) }
      value = entities.replace(value) { match ->
        val code = match.groupValues[1]
        val n = if (code.startsWith("x", true)) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
        if (n != null && n in 0..0x10ffff) String(Character.toChars(n)) else " "
      }
      value = value.replace(Regex("&(?:amp|nbsp|quot|apos|lt|gt);", RegexOption.IGNORE_CASE), " ")
      value = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).replace(marks, "").lowercase(java.util.Locale.ROOT)
      return value.map { confusables[it] ?: it }.joinToString("").replace(gaps, " ").trim()
    }

    fun skeleton(value: String) = value.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('7', 't')

    fun matches(raw: String): Boolean {
      if (AdultSourceIdentity.adultRepository(raw)) return true
      val words = normalize(raw).split(' ').take(256)
      for (i in words.indices) {
        var joined = ""
        for (j in i until minOf(words.size, i + 16)) {
          joined = (joined + skeleton(words[j])).replace(repeatedLetters, "$1")
          if (joined !in prefixes) break
          if (joined in aliases) return true
        }
      }
      return false
    }
  }

  private val fragments = listOf(
    "porn", "hub", "Porn", "HUB", "x", "videos", "xx", "nn", "pooorn", "h u b", "p0rn", "pornhub", "Brazzers", "bangbros",
    "The", "Sex", "Education", "Essex", "Movie", "2024", "S01E02", "1080p", "WEB-DL", ".", "_", "-", " ", "  ", "+", "18+",
    "%", "%20", "%2B", "%25", "%zz", "%70orn", "&", "&amp;", "&AMP;", "&nbsp;", "&#112;", "&#x70;", "&#xZZ;", "&#99999999;", "&lt;",
    "é", "É", "ﬁ", "ｐｏｒｎ", "рorn", "роrnhub", "αβγ", "­", "​", "é", "日本", "ß", "İ", "½", "²",
  )

  @Test fun `normalize and matches agree with the plain implementation`() {
    val random = java.util.Random(20260925)
    repeat(4000) {
      val input = buildString { repeat(1 + random.nextInt(6)) { append(fragments[random.nextInt(fragments.size)]) } }
      assertEquals("normalize($input)", Reference.normalize(input), AdultSourceIdentity.normalize(input))
      // Asked twice, so the remembered answer is checked as well as the first.
      assertEquals("normalize($input) again", Reference.normalize(input), AdultSourceIdentity.normalize(input))
      assertEquals("matches($input)", Reference.matches(input), AdultSourceIdentity.matches(input))
    }
  }
}
