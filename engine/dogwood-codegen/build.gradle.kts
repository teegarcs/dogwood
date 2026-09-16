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
 * `compose.material3` under Compose Multiplatform 1.10.3 resolves to
 * `org.jetbrains.compose.material3:material3:1.9.0` -- Material 3 has been versioned on its own
 * since 1.10 -- so that one is pinned here rather than read from the catalog, which only knows
 * the Compose Multiplatform version. The rest follow the catalog. See plans/generator-v2.md D-A.
 */
val composeSourceCoordinates = mapOf(
  "material3" to "org.jetbrains.compose.material3:material3:1.9.0",
  "foundation" to "org.jetbrains.compose.foundation:foundation:${libs.versions.composeMultiplatform.get()}",
  "foundation-layout" to "org.jetbrains.compose.foundation:foundation-layout:${libs.versions.composeMultiplatform.get()}",
  "ui" to "org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}",
)

val composeSourcesRoot: Provider<Directory> = layout.buildDirectory.dir("compose-sources")

val fetchComposeSources by tasks.registering {
  group = "build"
  description = "Resolves the pinned Compose Multiplatform sources jars and extracts commonMain"
  val resolved = composeSourceCoordinates.mapValues { (_, coords) ->
    configurations.detachedConfiguration(dependencies.create("$coords:sources@jar")).apply {
      isTransitive = false
    }
  }
  inputs.property("coordinates", composeSourceCoordinates)
  outputs.dir(composeSourcesRoot)
  doLast {
    val root = composeSourcesRoot.get().asFile
    root.deleteRecursively()
    for ((module, configuration) in resolved) {
      val jar = configuration.singleFile
      copy {
        from(zipTree(jar)) { include("commonMain/**/*.kt") }
        into(File(root, module))
      }
    }
  }
}

/*
 * The Material 3 tier (plans/generator-v2.md, M2). Segment 255, version 10900 -- the library's
 * own 1.9.0, encoded so a payload can declare it in its signed manifest and a host behind it
 * refuses before `start` (ADR-061). The lock and the exclusions live beside the module that
 * compiles the output, because that is where the failure they guard against shows up.
 */
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
  val lock = rootProject.file("dogwood-material3/androidx.material3.lock.json")
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
      "--module", "material3",
      "--wire-name", "androidx.material3",
      "--segment", "material3",
      "--segment-id", "255",
      "--version", "10900",
      "--guest-package", "dev.dogwood.compose.material3",
      "--host-package", "dev.dogwood.material3",
      "--guest-out", File(out, "guest/dev/dogwood/compose/material3").absolutePath,
      "--host-out", File(out, "host/dev/dogwood/material3").absolutePath,
      "--dictionary-out", File(out, "dictionary/androidx.material3.json").absolutePath,
      "--lock", lock.absolutePath,
      "--exclusions", exclusions.absolutePath,
      "--docs-out", reference.absolutePath,
    )
  }
}

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
