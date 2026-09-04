/*
 * Project Dogwood -- the vendored plural table, checked against the data it was copied from.
 *
 * Vendoring locale data is a bet that the copy is right today and stays right. The first half of
 * that bet is checkable and this file checks it: every locale the table names, against
 * International Components for Unicode for Java (ICU4J), which is the same Unicode Common Locale
 * Data Repository (CLDR) data Android reaches through `android.icu.text.PluralRules` and iOS has no
 * public access to at all.
 *
 * ICU4J is a **test-only** dependency. It is the oracle, not the implementation: shipping it to
 * answer one question would add a large library to every application, and it does not exist for
 * Kotlin/Native at all, which is the platform that needed the answer.
 *
 * The counts are chosen to land on the boundaries the rules actually turn on -- the teens, the
 * last-digit bands, the hundreds, and the round million that CLDR's newer `many` clause for the
 * Romance languages depends on -- rather than a range that would test 1..20 thoroughly and 21..∞
 * not at all.
 */
package dev.dogwood.host

import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every language subtag the vendored table names, which is exactly what must be verified. */
private val COVERED = listOf(
  "ja", "zh", "ko", "th", "vi", "id", "ms", "lo", "km", "my", "yue", "bo", "dz", "ig", "ii",
  "jv", "kde", "kea", "sah", "ses", "sg", "to", "wo", "yo", "jbo",
  "af", "an", "asa", "ast", "az", "bem", "bez", "bg", "brx", "ce", "cgg", "chr", "ckb", "de",
  "dv", "ee", "el", "en", "eo", "et", "eu", "fi", "fo", "fur", "gsw", "ha", "haw", "hu", "jgo",
  "jmc", "ka", "kaj", "kcg", "kk", "kkj", "kl", "ks", "ksb", "ku", "ky", "lb", "lg", "mas",
  "mgo", "ml", "mn", "mr", "nah", "nb", "nd", "ne", "nl", "nn", "nnh", "no", "nr", "ny", "nyn",
  "om", "or", "os", "pap", "ps", "rm", "rof", "rwk", "saq", "sd", "sdh", "seh", "sn", "so",
  "sq", "ss", "ssy", "st", "sv", "sw", "syr", "ta", "te", "teo", "tig", "tk", "tn", "tr", "ts",
  "ug", "ur", "uz", "ve", "vo", "vun", "wae", "xh", "xog", "da", "gl",
  "ca", "es", "it", "pt", "pt-PT",
  "fr", "ff", "hy", "kab",
  "hi", "bn", "gu", "kn", "fa", "am", "as", "zu", "doi", "pcm",
  "ru", "uk", "be", "sr", "hr", "bs", "pl", "cs", "sk", "sl", "mk", "is",
  "ar", "he", "lt", "lv", "prg", "ga", "gd", "cy", "mt", "ro", "fil",
)

/** Boundaries, not a range: the teens, each last-digit band, and the round million. */
private val COUNTS = buildList {
  addAll(0..25)
  addAll(listOf(30, 31, 32, 35, 39, 40, 100, 101, 102, 103, 105, 110, 111, 112, 114, 115, 119))
  addAll(listOf(120, 121, 122, 199, 200, 201, 1_000, 1_001, 999_999, 1_000_000, 2_000_000))
}

class PluralRulesAgreementTest {

  @Test
  fun theVendoredTableAgreesWithIcuOnEveryLocaleItClaims() {
    val disagreements = mutableListOf<String>()
    for (tag in COVERED) {
      val icu = PluralRules.forLocale(ULocale.forLanguageTag(tag))
      for (count in COUNTS) {
        val vendored = cldrPluralCategory(count, tag)
          ?: run { disagreements += "$tag: the table claims $tag and then returns null"; continue }
        val expected = icu.select(count.toDouble())
        if (vendored != expected) disagreements += "$tag n=$count: vendored $vendored, ICU $expected"
      }
    }
    assertEquals(
      emptyList(),
      // Truncated because a single wrong rule produces dozens of lines and the first few name it
      // precisely; the count says how deep the disagreement goes.
      disagreements.take(25),
      "${disagreements.size} disagreements with ICU4J",
    )
  }

  /**
   * Negative counts are folded onto their absolute value, as CLDR's `n` operand specifies.
   *
   * Worth pinning because it is reachable: a payload can send a negative count, and the operand
   * definition is the reason the answer is not simply "other".
   */
  @Test
  fun negativeCountsUseTheAbsoluteValue() {
    for (tag in listOf("en", "pl", "ru", "ar", "cy")) {
      for (count in 1..25) {
        assertEquals(
          cldrPluralCategory(count, tag),
          cldrPluralCategory(-count, tag),
          "$tag disagreed about $count and ${-count}",
        )
      }
    }
  }

  /** Underscores and casing are accepted, because a locale tag arrives from a platform, not a test. */
  @Test
  fun localeTagsAreNormalized() {
    assertEquals("few", cldrPluralCategory(3, "pl"))
    assertEquals("few", cldrPluralCategory(3, "PL"))
    assertEquals("few", cldrPluralCategory(3, "pl_PL"))
    assertEquals("few", cldrPluralCategory(3, "pl-PL"))
  }

  /**
   * Portuguese splits by region, which is the one case where the language subtag is not enough.
   *
   * European Portuguese takes the plain singular; Brazilian Portuguese counts zero as singular.
   */
  @Test
  fun portugueseSplitsByRegion() {
    assertEquals("one", cldrPluralCategory(0, "pt-BR"))
    assertEquals("one", cldrPluralCategory(0, "pt"))
    assertEquals("other", cldrPluralCategory(0, "pt-PT"))
  }

  /**
   * An unknown language returns null rather than a confident wrong answer.
   *
   * This is the whole reason the function is nullable: the caller reports the gap instead of
   * rendering an English category for a language whose reader would notice.
   */
  @Test
  fun anUnknownLanguageIsAdmittedRatherThanGuessed() {
    assertNull(cldrPluralCategory(3, "xx"))
    assertNotNull(cldrPluralCategory(3, "pl"))
  }

  /**
   * The table covers the languages a product is most likely to ship first.
   *
   * A guard against silent shrinkage: it is easy to delete a line from a `when` and much harder to
   * notice that Polish stopped being covered.
   */
  @Test
  fun theLanguagesMostLikelyToShipAreCovered() {
    val expected = listOf(
      "en", "es", "pt", "fr", "de", "it", "nl", "pl", "ru", "uk", "cs", "tr", "ar", "he",
      "ja", "ko", "zh", "hi", "id", "th", "vi", "sv", "da", "nb", "fi",
    )
    val missing = expected.filter { cldrPluralCategory(2, it) == null }
    assertTrue(missing.isEmpty(), "no plural rules for $missing")
  }

  /**
   * The English fallback now fires only for a language nobody named, and says so.
   *
   * Before the table this was the *normal* path on iOS and desktop for every language, and the
   * report said only which category was missing words -- which reads as a payload problem when it
   * was a host one.
   */
  @Test
  fun aLanguageWithNoRulesIsReportedAsSuch() {
    val skew = SkewReport()
    val recipe = """[16,3,{"one":"# thing","other":"# things"}]"""
    val rendered = ExpressionEvaluator(skew)
      .text(kotlinx.serialization.json.Json.parseToJsonElement(recipe), "xx-XX", "UTC")
    assertEquals("3 things", rendered, "the sentence must still render")
    assertTrue(
      skew.untranslatedPlurals.any { "no plural rules for" in it },
      "expected the missing-rules report, got ${skew.untranslatedPlurals}",
    )
  }

  /** And a language the table does know is not reported, so the signal stays worth reading. */
  @Test
  fun aLanguageWithRulesIsNotReported() {
    val skew = SkewReport()
    val recipe = """[16,3,{"one":"# rzecz","few":"# rzeczy","many":"# rzeczy","other":"# rzeczy"}]"""
    val rendered = ExpressionEvaluator(skew)
      .text(kotlinx.serialization.json.Json.parseToJsonElement(recipe), "pl-PL", "UTC")
    assertEquals("3 rzeczy", rendered)
    assertEquals(emptySet(), skew.untranslatedPlurals)
  }
}
