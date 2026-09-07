/*
 * Project Dogwood -- what the page tells the guest about itself.
 *
 * The mobile hosts hand the guest a `DogwoodServiceHost` and Zipline carries the objects across.
 * A Worker boundary cannot carry an object reference at all
 * ([ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md)), so the web profile
 * splits the same surface in two, by *when the answer is known*:
 *
 *   - **Facts the host knows at start** -- feature flags, the routes it handles, the launch
 *     parameters for this experience, the dictionary versions it implements. They cross once, in
 *     one message, before the first composition.
 *   - **Things the guest tells the host** -- an analytics event, a navigation request. They cross
 *     as one-way messages whenever the guest sends them.
 *
 * **What is deliberately not here: a request-and-answer channel.** `DogwoodClock` and
 * `DogwoodNetwork` are not in this class because on this platform the guest answers them itself: a
 * Worker has `Date.now()`, `Intl` and `fetch`. Routing them through the page would add a round trip
 * per call and buy nothing, because a Worker script the page loaded is inside the page's origin and
 * its own Content Security Policy -- which is exactly what ADR-032 records as *weaker than the
 * mobile allow-list, not equal to it*. A host that wants the mobile guarantee on the web sets a
 * Content Security Policy; there is no seam here that would give it one.
 *
 * The consequence, stated plainly because a reader will otherwise assume parity: on mobile
 * `network()` is enforced **by Dogwood**, and on the web it is enforced **by the browser**.
 *
 * **Why these live in the transport-free module.** The host half of this boundary is
 * Kotlin/WebAssembly and the guest half is Kotlin/JavaScript; they do not link, and the envelope's
 * *kind* constants are mirrored by hand in each because of it. The payloads are not: a hand-mirrored
 * `data class` is a second place a shape is known, and this repository has three separate ADRs
 * about what that costs. `dogwood-wire` is the one module both halves compile, so the shape is
 * declared once and serialised by the same generated code on both sides.
 */
package dev.dogwood.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The start payload, and the whole of what a page declares to a guest.
 *
 * @param entryPoint which experience to compose. The guest reads its own URL when this is blank,
 *   which is what the sample did before the host could say -- kept as a fallback so a guest served
 *   to an older page still renders something.
 * @param launchParams the destination's parameters, exactly as `DogwoodShell.activate` passes them
 *   on mobile. `JsonNull` means none.
 * @param featureFlags a snapshot, not a feed. A flag flipped while a screen is open does not reach
 *   that screen -- the same limit `DogwoodFeatureFlags` documents for every platform.
 * @param routes what this page will actually navigate to, so a guest can hide a control it cannot
 *   use. Empty means "this host does not enumerate", never "handles nothing".
 * @param segmentVersions the dictionary versions this client implements, which a guest branches on
 *   to decide what it may use. Omitting it is why the sample's Diagnostics screen read
 *   `surface revision 0 (unreported)` for as long as the web guest existed.
 */
@Serializable
data class WebStartPayload(
  val entryPoint: String = "",
  val launchParams: JsonElement = JsonNull,
  val featureFlags: Map<String, String> = emptyMap(),
  val routes: Set<String> = emptySet(),
  val segmentVersions: Map<String, Int> = emptyMap(),
)

/** One analytics event, as the guest sent it. */
@Serializable
data class WebAnalyticsEvent(val name: String, val properties: Map<String, String> = emptyMap())

/** One navigation request, as the guest sent it. */
@Serializable
data class WebNavigationRequest(val route: String, val params: JsonElement = JsonNull)
