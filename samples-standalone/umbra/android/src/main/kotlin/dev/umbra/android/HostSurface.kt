/*
 * Host API an Android adopter would actually reach for, touched so that compiling this file proves
 * the artifact is usable rather than merely present.
 *
 * Deliberately not a smoke test of one symbol. A published variant can exist and still be wrong --
 * missing a source set, missing a transitive dependency, or compiled against the wrong Java release
 * -- and each of those surfaces as a different failure here rather than as a green build.
 */
package dev.umbra.android

import dev.dogwood.host.DogwoodDictionary
import dev.dogwood.host.ReleaseGuard
import dev.dogwood.host.SkewReport

/** What this client can render, which is the first thing a host reports and a payload is checked against. */
fun segments(): Map<String, Int> = DogwoodDictionary.segmentVersions

/** Skew accumulates per experience; a host drains it to telemetry. */
fun freshReport(): SkewReport = SkewReport()

/** Named rather than constructed: the guard needs a store, and this file is a compile probe. */
val guardType: String = ReleaseGuard::class.simpleName ?: "unknown"
