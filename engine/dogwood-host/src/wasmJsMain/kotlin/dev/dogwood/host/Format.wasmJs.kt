/*
 * Project Dogwood -- formatting on the web, over the browser's own internationalisation.
 *
 * This actual is the one place where the web host is *better served than mobile by the platform*
 * rather than worse. The pinned QuickJS ships no ECMA-402 International application programming
 * interface (`Intl`), which is why money, dates and percentages cross the wire as recipes and are
 * rendered host-side at all. A browser has `Intl`, and it is the same data the platform formatters
 * on Android and iOS use, so these are thin wrappers rather than reimplementations.
 *
 * Plural rules are the exception and deliberately do **not** use `Intl.PluralRules`. The vendored
 * table (`PluralRules.kt`) is checked against International Components for Unicode for Java in
 * `PluralRulesAgreementTest`; routing one client to a different source would put a category this
 * project has verified beside one it has not, for no gain. See Layer 5 ADR-037.
 */
package dev.dogwood.host

private fun jsNumberFormat(locale: String, options: String, value: Double): String = js(
  "new Intl.NumberFormat(locale, JSON.parse(options)).format(value)",
)

private fun jsDateTimeFormat(locale: String, options: String, millis: Double): String = js(
  "new Intl.DateTimeFormat(locale, JSON.parse(options)).format(new Date(millis))",
)

private fun jsRelativeFormat(locale: String, value: Double, unit: String): String = js(
  "new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(value, unit)",
)

private fun jsResolvedTimeZone(): String = js(
  "Intl.DateTimeFormat().resolvedOptions().timeZone",
)

private fun jsCurrencyDigits(currencyCode: String): Int = js(
  """(function () {
    try {
      return new Intl.NumberFormat('en', { style: 'currency', currency: currencyCode })
        .resolvedOptions().maximumFractionDigits;
    } catch (e) { return -1; }
  })()""",
)

actual fun formatNumber(
  value: Double,
  locale: String,
  maximumFractionDigits: Int?,
  pattern: String?,
): FormattedNumber {
  // A guest-supplied pattern is untrusted input and `Intl` has no pattern syntax, so it is
  // refused and reported rather than approximated -- the same contract the other hosts keep when
  // a pattern will not parse. See ADR-024.
  if (pattern != null) {
    return FormattedNumber(plainNumber(value, locale, maximumFractionDigits), patternRejected = true)
  }
  return FormattedNumber(plainNumber(value, locale, maximumFractionDigits))
}

private fun plainNumber(value: Double, locale: String, maximumFractionDigits: Int?): String {
  val options = maximumFractionDigits?.let { """{"maximumFractionDigits":$it}""" } ?: "{}"
  return jsNumberFormat(locale, options, value)
}

/** The vendored table, deliberately -- not `Intl.PluralRules`. See this file's header. */
actual fun pluralCategory(count: Int, locale: String): String =
  cldrPluralCategory(count, locale) ?: if (count == 1) "one" else "other"

actual fun formatCurrency(minorUnits: Long, currencyCode: String, locale: String): String {
  // How many minor units make a major one is a property of the currency, and the browser knows it
  // -- `USD` two, `JPY` none, `KWD` three. An unknown code is skew, not a crash.
  val digits = jsCurrencyDigits(currencyCode)
  if (digits < 0) return "${plainNumber(minorUnits.toDouble(), locale, 0)} $currencyCode"
  var scale = 1.0
  repeat(digits) { scale *= 10 }
  return jsNumberFormat(
    locale,
    """{"style":"currency","currency":"$currencyCode"}""",
    minorUnits.toDouble() / scale,
  )
}

actual fun formatPercent(fraction: Double, locale: String, maximumFractionDigits: Int?): String {
  val digits = maximumFractionDigits ?: 0
  return jsNumberFormat(locale, """{"style":"percent","maximumFractionDigits":$digits}""", fraction)
}

actual fun formatDateTime(
  epochMillis: Long,
  locale: String,
  timeZoneId: String,
  style: Int,
): String {
  val fields = when (style) {
    0 -> """"dateStyle":"medium""""
    1 -> """"timeStyle":"short""""
    else -> """"dateStyle":"medium","timeStyle":"short""""
  }
  return jsDateTimeFormat(locale, """{$fields,"timeZone":"$timeZoneId"}""", epochMillis.toDouble())
}

actual fun formatRelativeTime(epochMillis: Long, nowMillis: Long, locale: String): String {
  val deltaSeconds = (epochMillis - nowMillis) / 1000.0
  val absolute = if (deltaSeconds < 0) -deltaSeconds else deltaSeconds
  val (amount, unit) = when {
    absolute < 60 -> deltaSeconds to "second"
    absolute < 3_600 -> deltaSeconds / 60 to "minute"
    absolute < 86_400 -> deltaSeconds / 3_600 to "hour"
    absolute < 2_592_000 -> deltaSeconds / 86_400 to "day"
    absolute < 31_536_000 -> deltaSeconds / 2_592_000 to "month"
    else -> deltaSeconds / 31_536_000 to "year"
  }
  val whole = if (amount < 0) -kotlin.math.floor(-amount) else kotlin.math.floor(amount)
  return jsRelativeFormat(locale, whole, unit)
}

actual fun systemTimeZoneId(): String = jsResolvedTimeZone()
