/*
 * Project Dogwood -- can Foundation be asked which plural category a count falls into?
 *
 * The platform review left iOS plural rules as an open question with two candidate answers:
 * borrow Foundation's rules, or vendor Unicode Common Locale Data Repository (CLDR) data. This
 * file is the first half, run rather than argued, because "Foundation has no public plural-category
 * selector" is a claim about an application programming interface surface and this project does not
 * accept those without a run.
 *
 * The trick under test: Foundation *does* apply real plural rules -- it just applies them while
 * rendering a `.stringsdict` entry rather than answering a question. So build a `.stringsdict`
 * whose every category maps to the literal name of that category, render it for a count, and read
 * the category back out of the rendered string.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithFormat
import platform.Foundation.writeToFile

/**
 * Every category mapped to its own name, so a rendered string *is* the category.
 *
 * [marker] is prefixed to every value so a reader can tell "Foundation applied the wrong rules"
 * apart from "Foundation never loaded this table" -- two very different findings that produce the
 * same-looking output otherwise. [categories] is a parameter because `zero`, `one` and `two` are
 * *both* Unicode category names and literal-count keys in a `.stringsdict`, and which one
 * Foundation is doing is the entire question.
 */
private fun stringsdict(marker: String, categories: List<String>): String = buildString {
  append("""<?xml version="1.0" encoding="UTF-8"?>""")
  append("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
  append("""<plist version="1.0"><dict><key>category</key><dict>""")
  append("""<key>NSStringLocalizedFormatKey</key><string>%#@v@</string>""")
  append("""<key>v</key><dict>""")
  append("""<key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>""")
  append("""<key>NSStringFormatValueTypeKey</key><string>d</string>""")
  for (category in categories) {
    append("<key>$category</key><string>$marker-$category</string>")
  }
  append("""</dict></dict></dict></plist>""")
}

class FoundationPluralProbeTest {

  /**
   * Writes a one-locale bundle whose only string maps each category to its own name.
   *
   * One locale per bundle deliberately: `NSBundle` chooses a localisation from the *process's*
   * preferred languages, not from a locale handed to a call, so the only way to pin the answer to
   * a language is to give the bundle nowhere else to go.
   */
  private fun probeBundle(language: String, categories: List<String>): NSBundle? {
    val root = NSTemporaryDirectory() + "dogwood-plural-probe-$language-${categories.size}"
    val lproj = "$root/$language.lproj"
    NSFileManager.defaultManager.createDirectoryAtPath(lproj, true, null, null)
    val written = (stringsdict(language, categories) as NSString)
      .writeToFile("$lproj/Localizable.stringsdict", true, NSUTF8StringEncoding, null)
    if (!written) {
      println("PROBE $language: could not write the stringsdict")
      return null
    }
    return NSBundle.bundleWithPath(root)
  }

  /**
   * Prints what Foundation answers for a matrix of languages and counts.
   *
   * A print rather than an assertion because this is a *probe*: the outcome decides an
   * architectural question, and a test that asserted the answer in advance would be assuming the
   * thing it exists to find out. What it must not do is pass silently while proving nothing, so it
   * asserts that the mechanism produced a format string at all.
   */
  @Test
  fun whatFoundationSaysAboutPluralCategories() {
    val counts = listOf(0, 1, 2, 3, 5, 11, 21, 22, 101)
    // Two passes. The first offers every category name; the second withholds the three that
    // double as literal-count keys, so a `few` for Polish two could only have come from real
    // rules. The temporary directory is per-language and per-pass for the same reason.
    for (categories in listOf(
      listOf("zero", "one", "two", "few", "many", "other"),
      listOf("few", "many", "other"),
    )) for (language in listOf("en", "pl", "ru", "ar", "cs", "ja", "fr")) {
      val bundle = probeBundle(language, categories)
      if (bundle == null) {
        println("PROBE $language: no bundle could be created")
        continue
      }
      val format = bundle.localizedStringForKey("category", null, null)
      val answers = counts.joinToString(" ") { count ->
        "$count=" + NSString.stringWithFormat(format, count)
      }
      println("PROBE $language offering=${categories.joinToString("/")} $answers")
      assertNotNull(format)
    }
  }

  /**
   * And what the platform actually answers now, on Kotlin/Native, through the shipped function.
   *
   * The agreement test compares the table against ICU4J on a Java Virtual Machine, which proves
   * the *table*. This proves the wiring: that `pluralCategory` on this platform reaches it, on the
   * target where the old answer was English for every language. Each case below is one the English
   * rule got wrong.
   */
  @Test
  fun theShippedFunctionUsesTheVendoredRulesOnKotlinNative() {
    assertEquals("few", pluralCategory(3, "pl-PL"), "Polish three")
    assertEquals("many", pluralCategory(5, "pl-PL"), "Polish five")
    assertEquals("few", pluralCategory(22, "ru-RU"), "Russian twenty-two")
    assertEquals("many", pluralCategory(11, "ru-RU"), "Russian eleven")
    assertEquals("two", pluralCategory(2, "ar-EG"), "Arabic two")
    assertEquals("zero", pluralCategory(0, "ar-EG"), "Arabic zero")
    assertEquals("other", pluralCategory(1, "ja-JP"), "Japanese has one form")
    assertEquals("one", pluralCategory(0, "fr-FR"), "French zero is singular")
    // And the fallback still exists for a language nobody named.
    assertEquals("other", pluralCategory(3, "xx-XX"))
  }
}
