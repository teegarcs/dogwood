plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
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
  inputs.dir(surface).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(generatedRoot)

  argumentProviders.add {
    val root = generatedRoot.get().asFile
    listOf(
      "--source", surface.absolutePath,
      "--segment", "dogwoodDesignSystem",
      "--segment-id", "1",
      "--version", "2",
      "--guest-package", "dev.dogwood.compose",
      "--host-package", "dev.dogwood.host",
      "--impl-package", "dev.dogwood.host",
      "--guest-out", File(root, "guest/dev/dogwood/compose/DesignSystemStubs.kt").absolutePath,
      "--host-out", File(root, "host/dev/dogwood/host/DesignSystemBindings.kt").absolutePath,
      "--dictionary-out", File(root, "dictionary/dogwood.designsystem.json").absolutePath,
      // Committed, unlike the generated sources: the lock is the record that tags never moved.
      "--lock", rootProject.file("surface/dogwood.designsystem.lock.json").absolutePath,
    )
  }
}
