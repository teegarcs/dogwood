/*
 * Project Dogwood -- Unicode plural categories, vendored.
 *
 * The host picks the category and the payload supplies the words. That split is settled
 * (`ADR-024`); what was not settled is where the *rules* come from on a platform whose standard
 * library will not answer the question.
 *
 * Android answers it: `android.icu.text.PluralRules.select` is real Unicode Common Locale Data
 * Repository (CLDR) data, reached by reflection because the source set is shared with a desktop
 * Java Virtual Machine that has no such class. Neither iOS nor desktop had any answer at all --
 * both fell back to the English rule, so one payload rendered correct Polish `few` on Android and
 * the wrong category on the other two, silently, for a reader of Polish.
 *
 * This file closes that. It is CLDR's cardinal rules, written out, for the counts this function can
 * actually be asked about.
 *
 * **Integers only, and that is what makes it tractable.** `pluralCategory` takes an `Int`, so the
 * CLDR operands collapse: `n` is the absolute value, `i` equals `n`, and `v`, `f`, `t` and `e` are
 * all zero. Most of the apparatus in a published rule -- the clauses that distinguish "1" from
 * "1.0" -- has no reachable case here and is not written. Where a rule's *only* difference from
 * another is a fraction clause, the two collapse into one set below, and the comment says so.
 *
 * **It is verified rather than trusted.** `PluralRulesAgreementTest` runs this table against ICU4J,
 * which is the same CLDR data the platform uses, across every locale named here and a matrix of
 * counts chosen to hit each rule's boundaries. Vendored locale data that nothing compares against
 * is a copy that silently ages; the comparison is what makes vendoring defensible.
 *
 * Decision record: `adrs/layer-5/ADR-037-plural-rules-are-vendored.md`.
 */
package dev.dogwood.host

/** The six Unicode plural categories. Not every locale uses more than one. */
private const val ZERO = "zero"
private const val ONE = "one"
private const val TWO = "two"
private const val FEW = "few"
private const val MANY = "many"
private const val OTHER = "other"

/**
 * The category [count] falls into in [languageTag], or null when this table does not know the
 * language.
 *
 * Null rather than a guess, so the caller can report the gap instead of rendering a wrong word
 * confidently. Only the language subtag is consulted: CLDR keys cardinal rules by language, and the
 * two exceptions that matter -- `pt-PT` against `pt-BR`, `sr`/`hr`/`bs` against each other -- are
 * handled explicitly below.
 */
internal fun cldrPluralCategory(count: Int, languageTag: String): String? {
  val n = if (count < 0) -count else count
  val normalized = languageTag.replace('_', '-').lowercase()
  val language = normalized.substringBefore('-')
  val region = normalized.substringAfter('-', "").substringBefore('-')

  return when (language) {
    // -------------------------------------------------------------------------------------
    // One category. A count changes nothing about the word.
    // -------------------------------------------------------------------------------------
    "ja", "zh", "ko", "th", "vi", "id", "ms", "lo", "km", "my", "yue", "bo", "dz", "ig", "ii",
    "jv", "kde", "kea", "sah", "ses", "sg", "to", "wo", "yo", "jbo", "root",
    -> OTHER

    // -------------------------------------------------------------------------------------
    // Two categories, singular at exactly one.
    // -------------------------------------------------------------------------------------
    "af", "an", "asa", "ast", "az", "bem", "bez", "bg", "brx", "ce", "cgg", "chr", "ckb", "de",
    "dv", "ee", "el", "en", "eo", "et", "eu", "fi", "fo", "fur", "gsw", "ha", "haw", "hu", "jgo",
    "jmc", "ka", "kaj", "kcg", "kk", "kkj", "kl", "ks", "ksb", "ku", "ky", "lb", "lg", "mas",
    "mgo", "ml", "mn", "mr", "nah", "nb", "nd", "ne", "nl", "nn", "nnh", "no", "nr", "ny", "nyn",
    "om", "or", "os", "pap", "ps", "rm", "rof", "rwk", "saq", "sd", "sdh", "seh", "sn", "so",
    "sq", "ss", "ssy", "st", "sv", "sw", "syr", "ta", "te", "teo", "tig", "tk", "tn", "tr", "ts",
    "ug", "ur", "uz", "ve", "vo", "vun", "wae", "xh", "xog", "da", "gl",
    -> if (n == 1) ONE else OTHER

    // Singular at one, plus the `many` category CLDR added for round millions. That clause is
    // about compact notation ("2M"), but it is stated on the integer operands, so a literal
    // 1000000 selects it and a table that omitted it would disagree with the platform.
    "ca", "es", "it", "pt" -> when {
      // Brazilian Portuguese counts zero as singular and European Portuguese does not, which is
      // the one place in this table where the language subtag alone gives the wrong answer. The
      // round-million clause below belongs to *both*, which the ICU comparison had to point out.
      n == 1 || (language == "pt" && region != "pt" && n == 0) -> ONE
      n != 0 && n % 1_000_000 == 0 -> MANY
      else -> OTHER
    }

    // Zero counts as singular. French says "0 jour", not "0 jours".
    "fr", "ff", "hy", "kab" -> when {
      language == "fr" && n != 0 && n % 1_000_000 == 0 -> MANY
      n == 0 || n == 1 -> ONE
      else -> OTHER
    }

    // Zero counts as singular, and so does any count whose integer part is zero -- which for
    // integers is the same statement, but the rule is written on `i = 0 or n = 1` upstream.
    "hi", "bn", "gu", "kn", "fa", "am", "as", "zu", "doi", "pcm" ->
      if (n == 0 || n == 1) ONE else OTHER

    // -------------------------------------------------------------------------------------
    // The Slavic family, which is where the English fallback did the most damage.
    // -------------------------------------------------------------------------------------
    // Russian, Ukrainian, Belarusian: `many` for the teens and for anything ending 0 or 5-9.
    "ru", "uk", "be" -> when {
      n % 10 == 1 && n % 100 != 11 -> ONE
      n % 10 in 2..4 && n % 100 !in 12..14 -> FEW
      else -> MANY
    }

    // Serbian, Croatian, Bosnian: the same first two clauses and **no** `many` at all for
    // integers -- upstream reserves `many` for fractions, which cannot reach this function.
    "sr", "hr", "bs", "sh" -> when {
      n % 10 == 1 && n % 100 != 11 -> ONE
      n % 10 in 2..4 && n % 100 !in 12..14 -> FEW
      else -> OTHER
    }

    // Polish: singular only at exactly one, and `many` swallows what Russian would call `many`
    // *and* the counts ending in 1 above eleven.
    "pl" -> when {
      n == 1 -> ONE
      n % 10 in 2..4 && n % 100 !in 12..14 -> FEW
      else -> MANY
    }

    // Czech and Slovak: `many` exists but only for fractions, so integers never reach it.
    "cs", "sk" -> when {
      n == 1 -> ONE
      n in 2..4 -> FEW
      else -> OTHER
    }

    // Slovenian counts by the last two digits, and has a genuine dual.
    "sl" -> when {
      n % 100 == 1 -> ONE
      n % 100 == 2 -> TWO
      n % 100 in 3..4 -> FEW
      else -> OTHER
    }

    // Macedonian: singular for anything ending in 1 except eleven.
    "mk" -> if (n % 10 == 1 && n % 100 != 11) ONE else OTHER

    // Icelandic: the same shape as Macedonian for integers.
    "is" -> if (n % 10 == 1 && n % 100 != 11) ONE else OTHER

    // -------------------------------------------------------------------------------------
    // Families with a dual, and the ones with the most categories.
    // -------------------------------------------------------------------------------------
    // Arabic uses all six, which is the standing counter-example to any two-form assumption.
    "ar", "ars" -> when {
      n == 0 -> ZERO
      n == 1 -> ONE
      n == 2 -> TWO
      n % 100 in 3..10 -> FEW
      n % 100 in 11..99 -> MANY
      else -> OTHER
    }

    // Hebrew, as CLDR states it since release 42: a dual, and nothing else.
    "he", "iw" -> when {
      n == 1 -> ONE
      n == 2 -> TWO
      else -> OTHER
    }

    // Lithuanian: `many` is fractions only, so integers see one, few and other.
    "lt" -> when {
      n % 10 == 1 && n % 100 !in 11..19 -> ONE
      n % 10 in 2..9 && n % 100 !in 11..19 -> FEW
      else -> OTHER
    }

    // Latvian and Prussian have a `zero` that is not only about zero: everything ending in 0,
    // and the whole teens range, takes it.
    "lv", "prg" -> when {
      n % 10 == 0 || n % 100 in 11..19 -> ZERO
      n % 10 == 1 && n % 100 != 11 -> ONE
      else -> OTHER
    }

    // Irish: four bands, by count rather than by last digit.
    "ga" -> when {
      n == 1 -> ONE
      n == 2 -> TWO
      n in 3..6 -> FEW
      n in 7..10 -> MANY
      else -> OTHER
    }

    // Scottish Gaelic: the same idea, with the teens joining their units.
    "gd" -> when {
      n == 1 || n == 11 -> ONE
      n == 2 || n == 12 -> TWO
      n in 3..10 || n in 13..19 -> FEW
      else -> OTHER
    }

    // Welsh uses five of the six, and its `many` is the single value six.
    "cy" -> when {
      n == 0 -> ZERO
      n == 1 -> ONE
      n == 2 -> TWO
      n == 3 -> FEW
      n == 6 -> MANY
      else -> OTHER
    }

    // Maltese: a dual, a `few` that includes zero, and a `many` over the teens.
    "mt" -> when {
      n == 1 -> ONE
      n == 2 -> TWO
      n == 0 || n % 100 in 3..10 -> FEW
      n % 100 in 11..19 -> MANY
      else -> OTHER
    }

    // Romanian: `few` covers zero and the whole 1-19 band by last two digits.
    "ro", "mo" -> when {
      n == 1 -> ONE
      n == 0 || (n % 100 in 1..19) -> FEW
      else -> OTHER
    }

    // Filipino and Tagalog: singular for one through three and for anything whose last digit is
    // not 4, 6 or 9 -- which is a much wider singular than most languages have.
    "fil", "tl" -> if (n in 1..3 || n % 10 !in setOf(4, 6, 9)) ONE else OTHER

    else -> null
  }
}
