/*
 * Project Dogwood -- locale-aware formatting on a Java Virtual Machine.
 *
 * `java.text` rather than `java.time.format` for numbers and currency because it is the API that
 * knows about currency fraction digits, and `java.time` for dates because it is the one that
 * knows about time zones. Both are present on Android from the minimum supported level.
 */
package dev.dogwood.host

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow

private fun locale(tag: String): Locale = Locale.forLanguageTag(tag).takeIf {
  it.language.isNotEmpty()
} ?: Locale.US

actual fun formatNumber(value: Double, locale: String, maximumFractionDigits: Int?): String =
  NumberFormat.getNumberInstance(locale(locale)).apply {
    if (maximumFractionDigits != null) {
      this.maximumFractionDigits = maximumFractionDigits
      this.minimumFractionDigits = maximumFractionDigits
    }
  }.format(value)

actual fun formatCurrency(minorUnits: Long, currencyCode: String, locale: String): String {
  val resolved = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
    // An unknown currency code is skew, not a crash: a payload may know about a currency this
    // client's runtime does not. Show the number and the code rather than nothing.
    ?: return "${formatNumber(minorUnits.toDouble(), locale, 0)} $currencyCode"

  val digits = resolved.defaultFractionDigits.coerceAtLeast(0)
  val amount = minorUnits.toDouble() / 10.0.pow(digits)
  return NumberFormat.getCurrencyInstance(locale(locale)).apply {
    currency = resolved
    maximumFractionDigits = digits
    minimumFractionDigits = digits
  }.format(amount)
}

actual fun formatPercent(fraction: Double, locale: String, maximumFractionDigits: Int?): String =
  NumberFormat.getPercentInstance(locale(locale)).apply {
    if (maximumFractionDigits != null) this.maximumFractionDigits = maximumFractionDigits
  }.format(fraction)

actual fun formatDateTime(
  epochMillis: Long,
  locale: String,
  timeZoneId: String,
  style: Int,
): String {
  val zone = runCatching { ZoneId.of(timeZoneId) }.getOrDefault(ZoneId.systemDefault())
  val formatter = when (style) {
    0 -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    1 -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
  }
  return formatter.withLocale(locale(locale)).withZone(zone).format(Instant.ofEpochMilli(epochMillis))
}

/**
 * Relative time, in whole units.
 *
 * `java.time.format.RelativeDateTimeFormatter` does not exist outside the International Components
 * for Unicode, and pulling that in for one string is not a trade worth making. This picks the
 * largest whole unit and asks the platform's plural-aware machinery for nothing, which means the
 * strings below are **English**. A product shipping other languages supplies its own implementation
 * of this function or puts the phrase in its own string table; the number is the part that had to
 * cross, and it did.
 */
actual fun formatRelativeTime(epochMillis: Long, nowMillis: Long, locale: String): String {
  val deltaMillis = epochMillis - nowMillis
  val past = deltaMillis < 0
  val seconds = abs(deltaMillis) / 1000
  val (amount, unit) = when {
    seconds < 60 -> seconds to "second"
    seconds < 3600 -> (seconds / 60) to "minute"
    seconds < 86_400 -> (seconds / 3600) to "hour"
    seconds < 2_592_000 -> (seconds / 86_400) to "day"
    seconds < 31_536_000 -> (seconds / 2_592_000) to "month"
    else -> (seconds / 31_536_000) to "year"
  }
  val plural = if (amount == 1L) unit else "${unit}s"
  return if (past) "$amount $plural ago" else "in $amount $plural"
}

actual fun systemTimeZoneId(): String = TimeZone.getDefault().id
