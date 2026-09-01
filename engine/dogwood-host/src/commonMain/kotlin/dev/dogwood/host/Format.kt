/*
 * Project Dogwood -- locale-aware formatting, host side.
 *
 * The one part of the resources subsystem that is not a matter of taste. The pinned QuickJS
 * (2021-03-27, via Zipline 1.27.0) ships **no ECMA-402 `Intl`**, so a guest has no locale-aware
 * number, currency, date or relative-time formatting available to it at any price. Every such
 * string has to be produced here.
 *
 * `expect`/`actual` because there is no common formatting library in Kotlin's standard library and
 * every platform already has a good one. The Java Virtual Machine and Android share an
 * implementation, which is the same split `ThreadIdentity` uses.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale

/** Formats [value] for [locale], with the locale deciding the fraction digits when null. */
expect fun formatNumber(value: Double, locale: String, maximumFractionDigits: Int?): String

/**
 * Formats an amount in a currency's smallest unit.
 *
 * Minor units, not a decimal amount, because the number of decimal places is a property of the
 * currency and the *host* is the side that knows it -- `USD` has two, `JPY` has none, `KWD` has
 * three. A guest sending "612.00" would already have made that decision wrongly.
 */
expect fun formatCurrency(minorUnits: Long, currencyCode: String, locale: String): String

expect fun formatPercent(fraction: Double, locale: String, maximumFractionDigits: Int?): String

/** [style] is 0 for date only, 1 for time only, 2 for both. */
expect fun formatDateTime(epochMillis: Long, locale: String, timeZoneId: String, style: Int): String

/** "3 days ago", "in 2 hours", in [locale]'s language. */
expect fun formatRelativeTime(epochMillis: Long, nowMillis: Long, locale: String): String

/**
 * Which locale and time zone the host formats in.
 *
 * The locale duplicates what the guest was told through `HostEnvironment`, deliberately: the
 * guest carries it so it can *branch*, and the host carries it so it can *format*. Those are two
 * different needs and collapsing them would mean either the guest formatting (which it cannot) or
 * the host trusting a value the guest could have changed.
 */
data class FormatContext(val locale: String, val timeZoneId: String)

/** Null means "derive it from the platform", resolved at the read site. */
val LocalFormatContext = compositionLocalOf<FormatContext?> { null }

@Composable
fun formatContext(): FormatContext {
  val provided = LocalFormatContext.current
  val locale = Locale.current.toLanguageTag()
  return provided ?: remember(locale) { FormatContext(locale, systemTimeZoneId()) }
}

/** The host's time zone, which the guest genuinely cannot obtain -- QuickJS ships no `Intl`. */
expect fun systemTimeZoneId(): String
