/*
 * Project Dogwood -- reading the host environment out of a browser.
 *
 * `HostEnvironment` is the one thing a guest cannot work out for itself: it composes without a
 * screen and has to be told the viewport, the density, the scheme and the locale. On Android the
 * host reads a `Configuration`; here it reads the window.
 *
 * Three of the mappings are worth stating, because a browser does not have the same concepts:
 *
 *   - **Density-independent pixels are CSS pixels.** A CSS pixel is defined as the same visual
 *     angle Android's density-independent pixel targets, and `window.innerWidth` is in CSS pixels
 *     already. So the viewport crosses unscaled and `devicePixelRatio` goes into `density`, where
 *     it describes the *device* rather than the units.
 *   - **Font scale has no browser equivalent worth guessing at.** A user's browser text-size
 *     preference is not readable from script; it manifests as a changed root font size, which a
 *     page can measure but which Compose Multiplatform does not consume. It is left at 1 rather
 *     than approximated, because an approximation here silently changes every text size on the
 *     page.
 *   - **Safe-area insets come from the `env()` CSS constants** on a page that opted into
 *     `viewport-fit=cover`, and are zero otherwise. They are read here so that an installed
 *     progressive web application on a notched phone lays out correctly, which is the case the
 *     field exists for.
 */
package dev.dogwood.web

import dev.dogwood.protocol.HostEnvironment
import kotlinx.browser.window

private fun prefersDark(): Boolean =
  js("window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches")

private fun documentIsRtl(): Boolean =
  js("getComputedStyle(document.documentElement).direction === 'rtl'")

private fun navigatorLanguage(): String = js("navigator.language || 'en-US'")

/**
 * Reads one of the safe-area environment constants, in CSS pixels.
 *
 * There is no property that exposes these directly, so the value is obtained the way every
 * library does it: set the constant as a length on a probe element and read the resolved value
 * back. Zero on any browser that does not define it, which is every desktop one.
 */
private fun safeArea(side: String): Int = js(
  """(function () {
    var probe = document.createElement('div');
    probe.style.position = 'fixed';
    probe.style.visibility = 'hidden';
    probe.style.height = 'env(safe-area-inset-' + side + ', 0px)';
    document.body.appendChild(probe);
    var value = parseFloat(getComputedStyle(probe).height) || 0;
    probe.remove();
    return Math.round(value);
  })()""",
)

/** The current environment, as this page sees it. */
fun readHostEnvironment(): HostEnvironment = HostEnvironment(
  density = window.devicePixelRatio.toFloat(),
  fontScale = 1f,
  darkMode = prefersDark(),
  layoutDirectionRtl = documentIsRtl(),
  viewportWidthDp = window.innerWidth,
  viewportHeightDp = window.innerHeight,
  safeAreaTopDp = safeArea("top"),
  safeAreaBottomDp = safeArea("bottom"),
  locale = navigatorLanguage(),
)

/**
 * Calls [onChange] whenever anything in [readHostEnvironment] could have changed.
 *
 * Resize and colour-scheme changes only. A locale change is not observable in a browser without a
 * reload, and pretending to watch for one would be a listener that never fires.
 */
fun observeHostEnvironment(onChange: (HostEnvironment) -> Unit) {
  val notify = { onChange(readHostEnvironment()) }
  window.addEventListener("resize", { notify() })
  addColorSchemeListener { notify() }
}

private fun addColorSchemeListener(callback: () -> Unit) {
  js(
    """(function () {
      var query = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
      if (!query) return;
      if (query.addEventListener) query.addEventListener('change', callback);
      else query.addListener(callback);
    })()""",
  )
}
