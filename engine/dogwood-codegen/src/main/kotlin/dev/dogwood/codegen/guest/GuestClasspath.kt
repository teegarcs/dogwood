/*
 * Project Dogwood -- the half of the authoring check that reads dependencies rather than code.
 *
 * `adrs/layer-5/ADR-050-the-authoring-check.md` §4 wrote this gap down as an unstated assumption
 * rather than pretending it was closed:
 *
 *   > **It does not check dependencies.** A guest that puts `androidx.compose.animation` on its
 *   > classpath and calls nothing from it passes, correctly; one that calls something reachable
 *   > only from there and not on this list passes too, incorrectly. A classpath check would catch
 *   > the second and is not built.
 *
 * This is that check. It is deliberately **not** a second source scan with a wider list: a source
 * scan cannot see an artifact that arrived transitively, which is the whole failure mode. A guest
 * that depends on some helper library which itself depends on `animation-core` has every
 * frame-clock Application Programming Interface (API) on its classpath, resolvable by name, with
 * nothing in its own source to show for it until somebody calls one.
 *
 * ## Why the two checks do not subsume each other, in either direction
 *
 * **The classpath check cannot replace the source scan.** `withFrameNanos` lives in
 * `androidx.compose.runtime:runtime`, which every guest must have -- it is what makes `remember`
 * and recomposition work, and `dogwood-compose` declares it as an `api` dependency. Banning the
 * artifact would ban the guest. So the frame loop stays on the source list, where it can be
 * rejected as a *call* without banning the module it lives in.
 *
 * **The source scan cannot replace the classpath check.** `animation-core` carries far more than
 * the eleven names on the source list -- every `*AsState` overload, every `Animatable` subtype,
 * every spec builder -- and a named list that tried to enumerate them would be the package pattern
 * ADR-050 refused, one name at a time.
 *
 * Together they cover a guest that names a forbidden call (source) and a guest that is one import
 * away from a hundred unnamed ones (classpath). Neither is a guarantee, and neither should be
 * described as one.
 *
 * ## Why it is a Gradle task and not a file reader
 *
 * The thing being inspected does not exist on disk until Gradle resolves it. A dependency graph is
 * produced by resolution -- version conflicts settled, platforms applied, substitutions performed
 * -- and reading `build.gradle.kts` would see the direct declarations only, which is precisely the
 * set that was never the problem. The Gradle half lives in
 * `dev.dogwood.codegen.gradle.DogwoodGuestClasspathCheckTask`; this file is the decision, kept free
 * of the Gradle API so it can be tested without a build.
 */
package dev.dogwood.codegen.guest

/** One artifact a guest may not have on its classpath, and what to do instead. */
data class BannedArtifact(
  val group: String,
  val name: String,
  val because: String,
  val instead: String,
) {
  val coordinate: String get() = "$group:$name"
}

/**
 * One module the resolution produced, and how it got there.
 *
 * [path] is the chain of coordinates from the module being checked to this one, the module's own
 * declared dependency first. It is the part of the report that matters most: a banned artifact is
 * almost never something the author typed, so a message that named only the artifact would send
 * somebody looking through their own build file for a line that is not there.
 */
data class ResolvedModule(
  val group: String,
  val name: String,
  val version: String,
  val path: List<String> = emptyList(),
) {
  val coordinate: String get() = "$group:$name"
}

/** A banned artifact found on a configuration, with the route it took to get there. */
data class ClasspathViolation(
  val configuration: String,
  val module: ResolvedModule,
  val artifact: BannedArtifact,
) {
  /** True when the author declared it themselves, which changes the advice. */
  val isDirect: Boolean get() = module.path.size <= 1

  override fun toString(): String = buildString {
    append("$configuration: ${module.coordinate}:${module.version} — ${artifact.because}\n")
    if (module.path.size > 1) {
      append("      reached by: ${module.path.joinToString(" -> ")}\n")
    } else {
      append("      declared directly by this module\n")
    }
    append("      instead: ${artifact.instead}")
  }
}

/**
 * The artifacts Layer 1's two obligations imply, and nothing beyond them.
 *
 * Group **and** module, never a group prefix. `androidx.compose.animation:animation-graphics` draws
 * animated vector drawables from a resource identifier and has no frame clock of its own; a
 * group-wide ban would reject it for the company it keeps, which is the "cries wolf" failure
 * ADR-050 warns about arriving by a different road.
 *
 * Both coordinate families are listed because both exist and a guest could resolve either. The
 * JetBrains `org.jetbrains.compose.*` artifacts are Compose Multiplatform's redistribution of the
 * same code, which is what a Kotlin/JavaScript guest would actually pull; the `androidx.compose.*`
 * ones are what an Android-flavoured module would.
 */
val BANNED_GUEST_ARTIFACTS: List<BannedArtifact> = listOf(
  // The frame clock. Every `*AsState` overload, `Animatable`, `updateTransition` and
  // `rememberInfiniteTransition` lives here, along with dozens of relatives the source list does
  // not name and should not try to.
  BannedArtifact(
    "androidx.compose.animation", "animation-core",
    "the frame-clock animation APIs: a guest animating one of these crosses the boundary every frame",
    "declare a target and let the host run the frames — animate(), animateDp(), oscillate(); see ADR-020",
  ),
  BannedArtifact(
    "org.jetbrains.compose.animation", "animation-core",
    "the frame-clock animation APIs: a guest animating one of these crosses the boundary every frame",
    "declare a target and let the host run the frames — animate(), animateDp(), oscillate(); see ADR-020",
  ),
  BannedArtifact(
    "androidx.compose.animation", "animation",
    "it brings animation-core with it, and adds AnimatedVisibility's per-frame state on top",
    "Presence(visible = …, enter = \"fade\", exit = \"shrinkVertically\"); see ADR-023",
  ),
  BannedArtifact(
    "org.jetbrains.compose.animation", "animation",
    "it brings animation-core with it, and adds AnimatedVisibility's per-frame state on top",
    "Presence(visible = …, enter = \"fade\", exit = \"shrinkVertically\"); see ADR-023",
  ),

  // Resources. The other half of the source list, and the artifact that makes the calls resolve.
  BannedArtifact(
    "org.jetbrains.compose.components", "components-resources",
    "the sandbox has no resources: no file system, no host resource identifiers, and a payload that ships months apart from its host",
    "AsyncImage(url), Icon(name) and payload-carried string tables; see ADR-017",
  ),
)

/**
 * The decision, over a resolved graph somebody else produced.
 *
 * Matching is on group and module and ignores the version, because a banned artifact is banned at
 * every version: the objection is to what is in it, not to which release it is.
 */
fun checkGuestClasspath(
  configuration: String,
  resolved: List<ResolvedModule>,
  banned: List<BannedArtifact> = BANNED_GUEST_ARTIFACTS,
): List<ClasspathViolation> = resolved.mapNotNull { module ->
  banned.firstOrNull { it.group == module.group && it.name == module.name }
    ?.let { ClasspathViolation(configuration, module, it) }
}

/** The message a build fails with. Names the artifact, the route it took, and the replacement. */
fun guestClasspathFailure(violations: List<ClasspathViolation>): String = buildString {
  appendLine("Dogwood: ${violations.size} forbidden artifact(s) on the guest classpath.")
  appendLine()
  appendLine("Nothing in this module's source has to call one for this to matter. An artifact on")
  appendLine("the classpath is an import away, and the source check can only see the names it")
  appendLine("knows — which is a fraction of what these modules export.")
  appendLine()
  for (violation in violations.distinctBy { it.configuration to it.module.coordinate }) {
    appendLine("  $violation")
  }
  appendLine()
  val transitive = violations.filterNot { it.isDirect }
  if (transitive.isNotEmpty()) {
    appendLine("A transitive one is removed where it enters, not where it lands:")
    appendLine()
    for (violation in transitive.distinctBy { it.module.coordinate }) {
      val entered = violation.module.path.first().substringBeforeLast(":")
      appendLine("      implementation(\"$entered\") {")
      appendLine("        exclude(group = \"${violation.module.group}\", module = \"${violation.module.name}\")")
      appendLine("      }")
    }
    appendLine()
  }
  appendLine("See specs/layer-1-authoring.md and adrs/layer-5/ADR-050-the-authoring-check.md.")
}
