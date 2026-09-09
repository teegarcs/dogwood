/*
 * Project Dogwood -- a guest that crashes on purpose, because two things need one to exist.
 *
 * A payload ships without store review, so a bad one ships fast. Two mechanisms exist for exactly
 * that moment and neither could be graded end to end without a payload that genuinely fails:
 *
 *   * **A crash a host can read** ([ADR-059](../../../../../../../adrs/layer-5/ADR-059-a-guest-crash-a-host-can-read.md)).
 *     The probe that produced that ADR found the crash was not merely unreadable but *unobservable*
 *     -- a throw inside a guest effect reached no host at all. The routing was fixed and unit tests
 *     cover it; what has never been run is a real crash in a real client.
 *   * **The crash-loop quarantine** ([ADR-049](../../../../../../../adrs/layer-5/ADR-049-surviving-a-bad-publish.md)).
 *     `ReleaseGuard` counts launches that were started and never reported successful, and refuses a
 *     release that has burned its attempts. Every test of it so far has *simulated* the crash by
 *     calling `starting` without `succeeded`; nothing has crashed.
 *
 * **It crashes from a `LaunchedEffect`, not from the composable body, and that is the whole design.**
 * A throw during composition is caught by the composition itself and surfaces as a failed frame; a
 * throw from a coroutine the guest launched is the one that used to vanish, because it lands on the
 * effect's `CoroutineContext` and there was no handler on it. The failure worth rehearsing is the
 * one that was invisible.
 *
 * **And it renders something first.** A screen that threw before drawing would make "the host saw a
 * crash" indistinguishable from "the payload never ran", which is precisely the confusion a crash
 * report exists to resolve. The marker below is composed, a frame goes out, and *then* it fails --
 * so a drill can assert that the guest was alive and then was not.
 *
 * The delay is the second half of that: long enough for a batch to be applied and read, short
 * enough that no drill has to wait on it.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.dogwood.compose.Column
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.Text
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.padding
import kotlinx.coroutines.delay

/** What a drill looks for to know the guest composed before it failed. */
const val CRASH_MARKER: String = "CRASH-COMPOSED"

/**
 * Renders one line and then throws from an effect.
 *
 * @param afterMillis how long to wait before failing. The default gives a host time to apply the
 *   first batch and a drill time to read it.
 */
@Composable
fun CrashScreen(afterMillis: Long = 1_500) {
  Column(modifier = Modifier.fillMaxWidth().padding(16)) {
    Text("Deliberate failure", style = "titleMedium")
    Text(
      "This screen exists to crash. It composes, a frame is applied, and then an effect throws " +
        "— which is the failure that used to reach no host at all.",
      style = "bodyMedium",
    )
    // The marker a drill asserts on, kept as its own node so that finding it means this screen
    // rendered rather than that some other text happened to contain the word.
    Text(CRASH_MARKER, style = "bodyMedium")
  }

  LaunchedEffect(Unit) {
    delay(afterMillis)
    // A distinctive message, because the point of the exercise is reading it on the other side. If
    // a host reports something that is not this string, the routing is not carrying what it thinks.
    error("dogwood deliberate guest crash: $CRASH_MARKER")
  }
}


/**
 * Throws from the composable body, before anything is drawn.
 *
 * The sibling of [CrashScreen], and the two are not interchangeable — they fail at different moments
 * and only one of them is a *bad publish*.
 *
 * `ReleaseGuard` persists an attempt before a release runs and marks it successful only once a guest
 * has started, produced a tree, and the host has mounted it (ADR-049). [CrashScreen] clears that bar
 * — it renders, a frame is applied, and only then does an effect throw — so the guard correctly
 * counts it a success. That is the right answer for that failure and the wrong fixture for this
 * claim: a crash-loop is the payload that never gets that far, and quarantining a payload that
 * worked and then broke would strand a fleet on an older release for a bug a user might never hit.
 *
 * So this one throws during composition, on the first pass, before the host has anything to mount.
 * Two launches of it burn the guard's attempts and the third is refused — which is `H2`, and which
 * until now had only ever been *simulated*, by calling `starting` without `succeeded` in a test.
 */
@Composable
fun CrashOnLaunchScreen() {
  // No marker and nothing composed above it, deliberately: anything drawn before the throw is
  // something the host could mount, and mounting is exactly what must not happen here.
  error("dogwood deliberate launch crash")
}
