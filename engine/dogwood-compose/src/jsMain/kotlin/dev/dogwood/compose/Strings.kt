/*
 * Project Dogwood -- localized strings, payload side.
 *
 * The resources subsystem needs "a localized-strings story (server-resolved or payload string
 * tables)". Under over-the-air delivery the answer is **payload string tables**, and the reason is
 * the whole point of the architecture: a screen's copy changes with the screen. A host-resolved
 * string table would put the words in the application binary and the layout in the payload, so
 * adding a row to a screen would need a store release to name it -- which is the coupling Dogwood
 * exists to remove.
 *
 * So this is guest code with no protocol at all. It selects on the language the host reported
 * through `HostEnvironment`, which the guest already has.
 *
 * **What this does not do is formatting.** No plural rules, no number or date substitution, no
 * currency. QuickJS ships no ECMA-402 `Intl`, so none of that is possible here at any price -- it
 * belongs to the host, through [Formats]. A table holds words; a recipe holds a number.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogwood.protocol.language

/**
 * Strings a payload carries, by language subtag.
 *
 * @param fallbackLanguage used when the device's language is not in the table. Required rather
 *   than optional: a screen that renders nothing because nobody translated it into Icelandic is a
 *   worse outcome than one that renders in English.
 */
class StringTable(
  private val byLanguage: Map<String, Map<String, String>>,
  private val fallbackLanguage: String = "en",
) {
  val languages: Set<String> get() = byLanguage.keys

  /**
   * @return the translation, the fallback language's version, or the key itself.
   *
   * The key rather than an empty string, because a missing translation should be *visible* in a
   * screenshot rather than a gap somebody has to notice.
   */
  fun get(language: String, key: String): String =
    byLanguage[language]?.get(key)
      ?: byLanguage[fallbackLanguage]?.get(key)
      ?: key

  fun has(language: String, key: String): Boolean = byLanguage[language]?.containsKey(key) == true
}

/** The table this experience is using. Static: a payload's strings do not change while it runs. */
val LocalStringTable = staticCompositionLocalOf { StringTable(emptyMap()) }

/**
 * Looks up a key in the device's language.
 *
 * Reads `HostEnvironment.language`, so a locale change re-renders exactly the nodes that call
 * this and nothing else.
 */
@Composable
@ReadOnlyComposable
fun strings(key: String): String =
  LocalStringTable.current.get(LocalHostEnvironment.current.language, key)
