/*
 * Project Dogwood -- locale-aware formatting on iOS.
 *
 * `NSNumberFormatter` and `NSDateFormatter` are the platform's own International Components for
 * Unicode bindings, so unlike the desktop Java Virtual Machine this actual has the real locale
 * data available to it for numbers, currency and dates.
 *
 * Two functions still fall back to English, and both are called out rather than hidden:
 * [pluralCategory], because Foundation exposes no public plural-category selector, and
 * [formatRelativeTime], which is written in whole units to match what every other host produces
 * for the same input. See the notes on each.
 */
package dev.dogwood.host

import kotlin.math.abs
import kotlin.math.pow
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterPercentStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.numberWithDouble
import platform.Foundation.timeZoneWithName

private fun locale(tag: String): NSLocale =
  NSLocale(localeIdentifier = tag.replace('-', '_').ifEmpty { "en_US" })

private fun decimalFormatter(locale: String, maximumFractionDigits: Int?) =
  NSNumberFormatter().apply {
    this.locale = locale(locale)
    numberStyle = NSNumberFormatterDecimalStyle
    if (maximumFractionDigits != null) {
      this.maximumFractionDigits = maximumFractionDigits.toULong()
      this.minimumFractionDigits = maximumFractionDigits.toULong()
    }
  }

private fun plainNumber(value: Double, locale: String, maximumFractionDigits: Int?): String =
  decimalFormatter(locale, maximumFractionDigits)
    .stringFromNumber(NSNumber.numberWithDouble(value)) ?: value.toString()

/**
 * The guest's pattern, the device's symbols -- the same contract the Java Virtual Machine actual
 * keeps.
 *
 * `NSNumberFormatter.positiveFormat` takes a Unicode Technical Standard #35 number pattern, which
 * is the same grammar `java.text.DecimalFormat` reads. Foundation does not throw on a malformed
 * pattern the way `DecimalFormat` does; it returns null from `stringFromNumber`, so the rejection
 * is detected there instead and reported the same way. A payload can be replaced over the air, so
 * a pattern is untrusted input either way.
 */
actual fun formatNumber(
  value: Double,
  locale: String,
  maximumFractionDigits: Int?,
  pattern: String?,
): FormattedNumber {
  if (pattern != null) {
    val formatted = decimalFormatter(locale, maximumFractionDigits).apply {
      positiveFormat = pattern
    }.stringFromNumber(NSNumber.numberWithDouble(value))
    if (formatted != null) return FormattedNumber(formatted)
    return FormattedNumber(plainNumber(value, locale, maximumFractionDigits), patternRejected = true)
  }
  return FormattedNumber(plainNumber(value, locale, maximumFractionDigits))
}

/**
 * The Unicode plural category.
 *
 * **English, and wrong for most languages** -- the same limitation the desktop Java Virtual
 * Machine actual carries and for the same reason: Foundation applies plural rules internally when
 * it renders a `.stringsdict` entry but exposes no way to ask which category a count falls into.
 * There is no public equivalent of `android.icu.text.PluralRules.select`. A product shipping iOS
 * in many languages supplies its own implementation of this function, exactly as the desktop note
 * says.
 */
actual fun pluralCategory(count: Int, locale: String): String =
  if (count == 1) "one" else "other"

actual fun formatCurrency(minorUnits: Long, currencyCode: String, locale: String): String {
  val formatter = NSNumberFormatter().apply {
    this.locale = locale(locale)
    numberStyle = NSNumberFormatterCurrencyStyle
    this.currencyCode = currencyCode
  }
  // How many minor units make a major one is a property of the currency, and Foundation answers
  // it by configuring the formatter from the code. An unknown code leaves the locale's own digit
  // count in place, which is a guess; the guess is only ever used to place a decimal point.
  val digits = formatter.maximumFractionDigits.toInt().coerceAtLeast(0)
  val amount = minorUnits.toDouble() / 10.0.pow(digits)
  formatter.minimumFractionDigits = digits.toULong()
  return formatter.stringFromNumber(NSNumber.numberWithDouble(amount))
  // An unrecognised currency code is skew, not a crash: show the number and the code, which is
  // what the Java Virtual Machine actual does when `Currency.getInstance` refuses.
    ?: "${plainNumber(minorUnits.toDouble(), locale, 0)} $currencyCode"
}

actual fun formatPercent(fraction: Double, locale: String, maximumFractionDigits: Int?): String =
  NSNumberFormatter().apply {
    this.locale = locale(locale)
    numberStyle = NSNumberFormatterPercentStyle
    if (maximumFractionDigits != null) this.maximumFractionDigits = maximumFractionDigits.toULong()
  }.stringFromNumber(NSNumber.numberWithDouble(fraction)) ?: "$fraction"

actual fun formatDateTime(
  epochMillis: Long,
  locale: String,
  timeZoneId: String,
  style: Int,
): String {
  val formatter = NSDateFormatter().apply {
    this.locale = locale(locale)
    timeZone = NSTimeZone.timeZoneWithName(timeZoneId) ?: NSTimeZone.localTimeZone
    when (style) {
      0 -> { dateStyle = NSDateFormatterMediumStyle; timeStyle = NSDateFormatterNoStyle }
      1 -> { dateStyle = NSDateFormatterNoStyle; timeStyle = NSDateFormatterShortStyle }
      else -> { dateStyle = NSDateFormatterMediumStyle; timeStyle = NSDateFormatterShortStyle }
    }
  }
  return formatter.stringFromDate(
    NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0),
  )
}

/**
 * Relative time, in whole units.
 *
 * Foundation has `NSRelativeDateTimeFormatter`, which is localised and which this deliberately
 * does not use: the Java Virtual Machine and Android actuals produce English whole-unit strings,
 * and a host that rendered "hace 3 días" on iOS and "3 days ago" everywhere else would be a
 * per-platform difference in what a payload says, which is the thing this architecture exists to
 * remove. The note on the Java Virtual Machine actual applies unchanged: a product shipping other
 * languages replaces this function on **every** host at once, or puts the phrase in its own
 * string table.
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

actual fun systemTimeZoneId(): String = NSTimeZone.localTimeZone.name
