/*
 * Umbra's web page, in outline: the four things a page does, against published artifacts.
 *
 * Not executed by the standalone check -- the verdict is that this compiles against
 * `dev.dogwood:dogwood-web` resolved from a repository. The shapes are the ones
 * `docs/getting-started.md` documents, so if that page and this file ever disagree, the compiler
 * says so here.
 */
package dev.umbra.web

import dev.dogwood.host.DogwoodDictionary
import dev.dogwood.host.DogwoodRegistry
import dev.dogwood.host.FileReleaseStore
import dev.dogwood.host.ReleaseGuard
import dev.dogwood.protocol.WebStartPayload
import dev.dogwood.web.DeliveryOutcome
import dev.dogwood.web.DogwoodWebExperience
import dev.dogwood.web.WebDelivery
import dev.dogwood.web.readHostEnvironment
import dev.umbra.design.UmbraDesignSystemBinding
import okio.Path.Companion.toPath

/**
 * The public half of the throwaway development key the guest signs with. A real page names its
 * own; `emptyMap()` is the written way to say "believe the origin" (ADR-062).
 */
private val TRUSTED_KEYS = mapOf(
  "dogwood-development" to "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102",
)

/** One registration, before anything renders -- the same single line every Dogwood host writes. */
fun registerUmbra() = DogwoodRegistry.register(UmbraDesignSystemBinding)

/** The experience: what this page tells the guest about itself, and where its reports go. */
fun umbraExperience(): DogwoodWebExperience = DogwoodWebExperience(
  readHostEnvironment(),
  report = { line -> println(line) },
  services = WebStartPayload(
    entryPoint = "home",
    segmentVersions = DogwoodDictionary.segmentVersions,
  ),
)

/**
 * Delivery: fetch and verify the sidecar, refuse a dictionary this client lacks, and only then
 * create the Worker. Suspending, because the manifest is fetched before any guest code runs.
 */
suspend fun startUmbra(experience: DogwoodWebExperience): DeliveryOutcome =
  WebDelivery(
    clientSegmentVersions = DogwoodDictionary.segmentVersions,
    report = { refusal -> println("delivery refused: ${refusal.message}") },
    releaseGuard = ReleaseGuard(
      store = FileReleaseStore(file = "/umbra/release.json".toPath()),
      onReport = { println("release guard: $it") },
    ),
    trustedPublicKeys = TRUSTED_KEYS,
  ).start("dogwood-manifest.json", experience)

/** What a page does with the outcome: attach the bridge, or show its own screen. */
fun attach(experience: DogwoodWebExperience, outcome: DeliveryOutcome) {
  when (outcome) {
    is DeliveryOutcome.Started -> experience.attach(outcome.bridge)
    is DeliveryOutcome.Refused -> Unit
  }
}
