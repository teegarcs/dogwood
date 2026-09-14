/*
 * Project Dogwood -- the real guest, compiled for a Web Worker.
 *
 * `plans/production-readiness.md` called this the largest hole in the alignment story, and it was:
 * `web-slice`'s guest is a hundred lines of hand-written JavaScript. That was the right thing to
 * build -- a hand-written guest proves the protocol is an interface rather than an artefact of
 * having Kotlin on both ends -- and it left the architecture's central claim undemonstrated on the
 * web, because **no Kotlin/Compose guest had ever run in a Worker**.
 *
 * This is that guest, and the interesting thing about it is how little there is. `DogwoodGuest`,
 * `DogwoodComposition`, the applier, the recorder, every screen and every generated stub are the
 * same code the mobile payload runs. What differs is the transport, and since 2026-09-13 the
 * transport is a library -- `runInWorker` in `dogwood-compose` -- so what a Worker entry point
 * contains is the entry-point list and one call. This file used to carry the four hundred lines
 * that call now hides, and a product would have had to copy them.
 *
 * **The screens do not know which transport they are on.** They are in `samples/slice-screens`,
 * they name no transport, and they are compiled here unchanged.
 */
package dev.dogwood.slice.web

import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.compose.runInWorker
import dev.dogwood.slice.AboutScreen
import dev.dogwood.slice.AppShell
import dev.dogwood.slice.CrashOnLaunchScreen
import dev.dogwood.slice.CrashScreen
import dev.dogwood.slice.ExploreScreen
import dev.dogwood.slice.FeedScreen
import dev.dogwood.slice.exploreParams

/**
 * The same entry points the mobile payload offers, from the same screens.
 *
 * If this list and `slice-guest`'s ever disagree, one of the two platforms is running different
 * product code, which is the thing this module exists to make impossible to do by accident.
 */
private val guest = DogwoodGuest(
  "explore" to { params -> ExploreScreen(exploreParams(params)) },
  "about" to { _ -> AboutScreen() },
  "feed" to { _ -> FeedScreen() },
  "app" to { params -> AppShell(exploreParams(params)) },
  // A payload that fails on purpose, so that the two mechanisms built for a bad publish
  // -- a readable crash (ADR-059) and the crash-loop quarantine (ADR-049) -- can be graded
  // against a real failure instead of a simulated one. See `CrashScreen.kt`.
  "crash" to { _ -> CrashScreen() },
  // ...and one that never gets far enough to be mounted, which is what a crash-loop is.
  "crash-launch" to { _ -> CrashOnLaunchScreen() },
)

fun main() = runInWorker(guest)
