/*
 * Project Dogwood -- the data the boundary carries, as opposed to the services that carry it.
 *
 * `HostEnvironment` and `StateSnapshot` are plain serializable values with no transport in them, and
 * they live here rather than beside the Zipline service interfaces because the web profile has no
 * Zipline: a guest in a Web Worker still needs to be told the size of its viewport and still needs
 * its state carried across a code update. Splitting them out is what lets the same types reach a
 * platform that cannot link an interpreter.
 */
package dev.dogwood.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Host-owned facts a guest composition may read: the device, and how it is set up.
 *
 * Named for what it is rather than for the framework. A guest reads `LocalHostEnvironment`, not
 * a configuration object -- there is only one environment and it belongs to the host.
 *
 * Locale is present twice over: the resources subsystem needs it, and the pinned QuickJS
 * ships no ECMA-402 `Intl`, so guest-side locale-aware formatting has no built-in primitive.
 */
@Serializable
data class HostEnvironment(
  val density: Float = 1f,
  val fontScale: Float = 1f,
  val darkMode: Boolean = false,
  val layoutDirectionRtl: Boolean = false,
  val viewportWidthDp: Int = 0,
  val viewportHeightDp: Int = 0,
  val safeAreaTopDp: Int = 0,
  val safeAreaBottomDp: Int = 0,
  val locale: String = "en-US",
)

/** Implemented by the host, called by the guest. */

/**
 * Guest state, captured so it can survive a code update.
 *
 * Layer 4 is explicit that a code update while a screen is live is the NORMAL case, since
 * `ZiplineLoader.load()` returns a flow. Without this, every update loses scroll position,
 * expanded rows, and half-typed text.
 *
 * The values are whatever the guest's `rememberSaveable` call sites produced. They cross the
 * boundary, so they must be serializable -- which is a real constraint on what a guest may
 * declare saveable, not an implementation detail.
 */
@Serializable
data class StateSnapshot(
  val values: Map<String, List<JsonElement>> = emptyMap(),
) {
  val isEmpty: Boolean get() = values.isEmpty()
}

/** Implemented by the guest, called by the host. */
