plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  // The generator is consumed two ways and both are real. The engine's own build invokes the
  // command-line entry point directly, because it is one project away and has no need of a plugin;
  // a product applies the plugin, because fourteen command-line arguments are not an interface.
  `java-gradle-plugin`
  `maven-publish`
}

group = "io.github.teegarcs"
version = "0.1.0"

gradlePlugin {
  plugins {
    create("dogwood") {
      id = "io.github.teegarcs.dogwood.codegen"
      implementationClass = "dev.dogwood.codegen.gradle.DogwoodPlugin"
      displayName = "Dogwood component generator"
      description = "Generates guest stubs and host bindings from a component surface"
    }
    create("dogwoodGuest") {
      id = "io.github.teegarcs.dogwood.guest"
      implementationClass = "dev.dogwood.codegen.gradle.DogwoodGuestPlugin"
      displayName = "Dogwood guest authoring check"
      description = "Rejects guest code that would tick the boundary every frame"
    }
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    // The compiler's parser entry points are marked as K1 API. Parsing declarations is exactly
    // what this generator needs; the K2 analysis API is a much larger dependency for no gain
    // until the generator needs resolved types rather than declaration text.
    optIn.add("org.jetbrains.kotlin.K1Deprecation")
  }
}

dependencies {
  implementation(libs.kotlin.compiler.embeddable)
  implementation(libs.serialization.json)
  // The plugin reads the Kotlin plugin's own extensions to attach generated sources to the right
  // source set. `compileOnly`: the consumer's build already has it, and shipping a second copy in
  // a plugin classpath is how two Kotlin Gradle plugins end up in one build.
  compileOnly(libs.kotlin.gradle.plugin)
  testImplementation(kotlin("test"))
}

/**
 * Runs the generator over the committed surface.
 *
 * Output goes to a build directory and is never committed: generated code in version control is a
 * copy that drifts. Both consumers add these directories as source directories and depend on this
 * task, so a change to the surface cannot compile without regenerating.
 */
val generatedRoot: Provider<Directory> = rootProject.layout.buildDirectory.dir("generated/dogwood")

/*
 * Generator v2's input: the Compose Multiplatform sources the host is compiled against.
 *
 * Not a checkout of androidx and not a metalava dump. The `-sources.jar` of the exact artifact
 * the host resolves is the one text that cannot disagree with the binary the bindings call, and
 * it carries default expressions, which a signature dump does not (ADR-002). Only `commonMain/`
 * is extracted: the common source set is the surface every host shares.
 *
 * The VERSION is not written here. `compose.material3` is an alias the Compose Multiplatform
 * plugin maps to a Material 3 version of its own choosing -- 1.10.3 maps to 1.9.0, because
 * Material 3 has been versioned separately since 1.10 -- and a number typed here would be a second
 * opinion about that mapping, silently wrong the day the plugin moves. So each module's version is
 * resolved off `dogwood-host`'s own compile classpath, which is by definition what the host
 * compiles against, and written to `versions.json` beside the sources for the generator to read.
 * See ADR-073 and plans/material3-proof.md section 3.
 */
val composeSourceModules = mapOf(
  "material3" to "org.jetbrains.compose.material3:material3",
  "foundation" to "org.jetbrains.compose.foundation:foundation",
  "foundation-layout" to "org.jetbrains.compose.foundation:foundation-layout",
  "ui" to "org.jetbrains.compose.ui:ui",
)

/*
 * What the host resolves, as a task input.
 *
 * A `Provider` rather than a value, so the resolution happens when Gradle snapshots the task's
 * inputs rather than while this file is being configured -- which both avoids resolving another
 * project's configuration during configuration and, more importantly, makes the resolved version
 * part of the up-to-date check. A version that changed while the coordinates did not has to
 * invalidate the fetch, or the generator would read last week's sources for this week's host: the
 * exact failure this indirection exists to prevent.
 */
val resolvedComposeVersions: Provider<Map<String, String>> = provider {
  val classpath = project(":dogwood-host").configurations.getByName("jvmCompileClasspath")
  val resolved = classpath.incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
  composeSourceModules.mapValues { (_, coordinate) ->
    val (group, name) = coordinate.split(":")
    // The root Kotlin Multiplatform module is what the classpath names; its platform variant
    // (`-jvm`, `-desktop`, `-android`) is the fallback, because which one appears depends on how
    // the variant was published. An exact match is tried first: `foundation` and
    // `foundation-layout` are two modules, and a prefix match would confuse them.
    val exact = resolved.firstOrNull { it.group == group && it.name == name }
    val variant = resolved.firstOrNull {
      it.group == group && it.name in setOf("$name-jvm", "$name-desktop", "$name-android")
    }
    (exact ?: variant)?.version ?: error(
      "dogwood-host's compile classpath resolves no $coordinate. The generator reads the sources " +
        "of what the host compiles against; a module the host does not link is a module no " +
        "payload could call.",
    )
  }
}

val composeSourcesRoot: Provider<Directory> = layout.buildDirectory.dir("compose-sources")
val composeVersionsFile: Provider<RegularFile> = composeSourcesRoot.map { it.file("versions.json") }

val fetchComposeSources by tasks.registering {
  group = "build"
  description = "Extracts commonMain from the sources jars of the Compose artifacts the host resolves"
  inputs.property("modules", composeSourceModules)
  inputs.property("versions", resolvedComposeVersions)
  outputs.dir(composeSourcesRoot)
  doLast {
    val versions = resolvedComposeVersions.get()
    val root = composeSourcesRoot.get().asFile
    root.deleteRecursively()
    for ((module, coordinate) in composeSourceModules) {
      val configuration = configurations
        .detachedConfiguration(dependencies.create("$coordinate:${versions.getValue(module)}:sources@jar"))
        .apply { isTransitive = false }
      copy {
        from(zipTree(configuration.singleFile)) { include("commonMain/**/*.kt") }
        into(File(root, module))
      }
    }
    File(root, "versions.json").writeText(
      versions.entries.sortedBy { it.key }
        .joinToString(",\n", "{\n", "\n}\n") { "  \"${it.key}\": \"${it.value}\"" },
    )
    logger.lifecycle(
      "compose sources: " + versions.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" },
    )
  }
}

/*
 * The Material 3 tier (plans/generator-v2.md, M2). Segment 255. Its version is the library's own,
 * encoded (1.9.0 is 10900) so a payload can declare it in its signed manifest and a host behind it
 * refuses before `start` (ADR-061) -- derived by the generator from `versions.json` rather than
 * passed in, since ADR-073. The lock and the exclusions live beside the module that compiles the
 * output, because that is where the failure they guard against shows up.
 */
val material3Lock: File = rootProject.file("dogwood-material3/androidx.material3.lock.json")

val generateMaterial3 by tasks.registering(JavaExec::class) {
  group = "build"
  description = "Generates the Material 3 tier: guest stubs, host bindings, dictionary and lock"
  dependsOn(fetchComposeSources)
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.v2.MainKt")
  // Its own root, beside `generated/dogwood` rather than inside it. `generateDesignSystem` declares
  // the whole of `generated/dogwood` as its output, so a tier written underneath it is, to Gradle,
  // that task's output being read by a sources jar with no dependency on it -- and the first
  // `publishToMavenLocal` after the tier existed failed on exactly that. Separate roots, no overlap.
  val root = rootProject.layout.buildDirectory.dir("generated/dogwood-material3")
  val exclusions = rootProject.file("dogwood-material3/exclusions.txt")
  val lock = material3Lock
  val reference = rootProject.file("../docs/api/androidx.material3.md")
  inputs.dir(composeSourcesRoot)
  inputs.file(exclusions)
  outputs.dir(root)
  outputs.file(reference)
  argumentProviders.add {
    val out = root.get().asFile
    listOf(
      "generate",
      "--sources", composeSourcesRoot.get().asFile.absolutePath,
      "--versions", composeVersionsFile.get().asFile.absolutePath,
      "--module", "material3",
      "--wire-name", "androidx.material3",
      "--segment", "material3",
      "--segment-id", "255",
      "--guest-package", "dev.dogwood.compose.material3",
      "--host-package", "dev.dogwood.material3",
      "--guest-out", File(out, "guest/dev/dogwood/compose/material3").absolutePath,
      "--host-out", File(out, "host/dev/dogwood/material3").absolutePath,
      "--dictionary-out", File(out, "dictionary/androidx.material3.json").absolutePath,
      "--lock", lock.absolutePath,
      "--exclusions", exclusions.absolutePath,
      "--docs-out", reference.absolutePath,
    ) + if (providers.gradleProperty("dogwoodAcceptTierDowngrade").getOrElse("false") == "true") {
      listOf("--accept-downgrade", "true")
    } else {
      emptyList()
    }
  }
}

/*
 * The cross-check the derivation still needs.
 *
 * Deriving the version removes the three numbers a person had to keep in step; it does not by
 * itself tell anyone that the *committed* lock -- which is what a payload's declaration is
 * compared against -- describes the library this checkout resolves. A Compose Multiplatform bump
 * that moves the Material 3 mapping and is committed without regenerating leaves exactly that
 * disagreement, and the symptom would be a payload declaring a version no host has.
 *
 * So: read the committed lock, read what the host resolves, and refuse if they differ. Cheap
 * enough to run in `check`, and watched to fail on a hand-edited lock before it was believed.
 */
val checkGeneratedTierVersions by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Refuses a committed tier lock whose version is not the one the host resolves"
  dependsOn(fetchComposeSources)
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.v2.MainKt")
  inputs.dir(composeSourcesRoot)
  inputs.file(material3Lock)
  argumentProviders.add {
    listOf(
      "check-versions",
      "--versions", composeVersionsFile.get().asFile.absolutePath,
      "--tiers", "material3=${material3Lock.absolutePath}",
    )
  }
}

tasks.named("check") { dependsOn(checkGeneratedTierVersions) }

val generateComposeCoverage by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Measures what generator v2 can bind of the pinned Compose surface (tools/generator-v2/coverage.md)"
  dependsOn(fetchComposeSources)
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.v2.MainKt")
  val report = rootProject.file("../tools/generator-v2/coverage.md")
  inputs.dir(composeSourcesRoot)
  outputs.file(report)
  outputs.file(rootProject.file("../tools/generator-v2/coverage.json"))
  argumentProviders.add {
    listOf(
      "coverage",
      "--sources", composeSourcesRoot.get().asFile.absolutePath,
      "--versions", composeVersionsFile.get().asFile.absolutePath,
      "--out", report.absolutePath,
      "--exclusions", rootProject.file("dogwood-material3/exclusions.txt").absolutePath,
    )
  }
}

/*
 * The design system's dictionary version, on its own line and under its own name.
 *
 * The skew drills bump this to N+1 by patching the build file, and until 2026-09-15 they did so by
 * finding the first `"--version"` literal in it. The Material 3 tier's task (`generateMaterial3`,
 * above) now declares one first -- `10900`, the library's version -- so the drills bumped the wrong
 * tier, the design-system lock refused the drill's added components as "added without raising the
 * version", and both skew drills failed on the first run after the tier existed. A named value is
 * something a patch can find without guessing.
 */
val designSystemVersion = 15

val generateDesignSystem by tasks.registering(JavaExec::class) {
  group = "build"
  description = "Generates guest stubs, host bindings and the dictionary from the component surface"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.MainKt")

  val surface = rootProject.file("surface")
  val reference = rootProject.file("../docs/api/dogwood.designsystem.md")
  inputs.dir(surface).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(generatedRoot)
  // Declared, so deleting the reference regenerates it rather than leaving the task up to date
  // with a missing output. It lives in the source tree on purpose; see `Docs.kt`.
  outputs.file(reference)

  argumentProviders.add {
    val root = generatedRoot.get().asFile
    listOf(
      "--source", surface.absolutePath,
      "--segment", "dogwoodDesignSystem",
      // What a guest calls it. Dotted on the wire, camel-cased in Kotlin; the generator used to
      // reconcile the two with a `replace()` and that stopped being enough once a segment had to
      // name itself in more than one emitted file.
      "--wire-name", "dogwood.designsystem",
      "--segment-id", "1",
      // VerticalList and HorizontalList are hand-written in this same segment, because the
      // generator does not model lazy layouts yet. Their tags are not the generator's to give.
      // 21 is `Pager`, hand-written for the same reason 10 and 11 are: a lazy layout needs
      // per-page access to its children, which the generator refuses to bind (and refuses
      // correctly). Reserving it is what stops the generator handing tag 21 to the next
      // component somebody appends -- a collision renders the wrong widget rather than failing.
      "--reserved", "10,11,21",
      "--version", designSystemVersion.toString(),
      "--guest-package", "dev.dogwood.compose",
      "--host-package", "dev.dogwood.host",
      "--impl-package", "dev.dogwood.host",
      "--guest-out", File(root, "guest/dev/dogwood/compose/DesignSystemStubs.kt").absolutePath,
      "--host-out", File(root, "host/dev/dogwood/host/DesignSystemBindings.kt").absolutePath,
      "--dictionary-out", File(root, "dictionary/dogwood.designsystem.json").absolutePath,
      "--wire-out", File(root, "wire/dev/dogwood/protocol/DogwoodSegments.kt").absolutePath,
      // Committed, unlike the generated sources: the lock is the record that tags never moved.
      "--lock", rootProject.file("surface/dogwood.designsystem.lock.json").absolutePath,
      // Also committed, and for a related reason: a reference generated into a build directory is
      // a reference nobody reads. See `Docs.kt`.
      "--docs-out", rootProject.file("../docs/api/dogwood.designsystem.md").absolutePath,
    )
  }
}
