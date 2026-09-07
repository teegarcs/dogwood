plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  // The generator is consumed two ways and both are real. The engine's own build invokes the
  // command-line entry point directly, because it is one project away and has no need of a plugin;
  // a product applies the plugin, because fourteen command-line arguments are not an interface.
  `java-gradle-plugin`
  `maven-publish`
}

group = "dev.dogwood"
version = "0.1.0"

gradlePlugin {
  plugins {
    create("dogwood") {
      id = "dev.dogwood.codegen"
      implementationClass = "dev.dogwood.codegen.gradle.DogwoodPlugin"
      displayName = "Dogwood component generator"
      description = "Generates guest stubs and host bindings from a component surface"
    }
    create("dogwoodGuest") {
      id = "dev.dogwood.guest"
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
      "--reserved", "10,11",
      "--version", "10",
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
