import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":dogwood-host"))
  implementation(project(":samples:product-design-system"))
  implementation(project(":dogwood-material3"))
  implementation(compose.desktop.currentOs)
  implementation(libs.coroutines.core)
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}

compose.desktop {
  application {
    mainClass = "dev.dogwood.slice.desktop.MainKt"
    // QuickJS composition is deeply recursive and interpreted frames are heavy; Zipline expects
    // callers to use an eight-megabyte stack.
    jvmArgs += listOf("-Xss8m")
    // Passed through so `-Ddogwood.manifest=…` on the Gradle command line reaches the application.
    // Without this the property is set on Gradle's own JVM and the sample never sees it -- which
    // is how a run against the reference server silently loaded from the development task instead.
    System.getProperty("dogwood.manifest")?.let { jvmArgs += "-Ddogwood.manifest=$it" }
    // Same passthrough for the skew drill's flag, and it is a *program* argument rather than a
    // system property because that is what `main(args)` reads -- the drill turns this host into its
    // own instrument and the flag is part of how it was launched, not part of its configuration.
    if (System.getProperty("dogwood.skew") == "true") args += "--dogwood-skew"
    nativeDistributions { targetFormats(TargetFormat.Dmg) }
  }
}

// The Compose desktop plugin registers `run` late, so configure it once it exists.
tasks.matching { it.name == "run" }.configureEach {
  this as JavaExec
  // The guest is resolved relative to the build root, not this module.
  workingDir = rootProject.projectDir
  dependsOn(":samples:slice-guest:jsBrowserProductionWebpackZipline")
}


/*
 * The runtime classpath, written to a file, so a drill can run this client **without Gradle**.
 *
 * The skew drill's whole shape is two builds: a client compiled against the committed surface, and
 * a payload compiled against a surface one version newer. On Android and iOS that separation is
 * free, because the client is an installed artifact and the drill simply does not reinstall it.
 *
 * On the desktop it is not free, and the first attempt proved it: `./gradlew run` after patching the
 * surface recompiles `:dogwood-host` against the *patched* surface, and the run fails to build at
 * all -- "No parameter with name 'tone' found" -- because the generator has emitted bindings for
 * components whose implementations do not exist. `-x` on the sample's own compile task does not
 * help; the dependency is deeper than that. Anything that leaves Gradle in the loop rebuilds both
 * halves and tests nothing.
 *
 * So the drill resolves the classpath *before* patching and launches a plain `java` afterwards.
 */
val writeRuntimeClasspath by tasks.registering {
  description = "Writes the runtime classpath to build/runtime-classpath.txt for the skew drill."
  val runtimeClasspath = configurations.named("runtimeClasspath")
  val classes = tasks.named("jar")
  dependsOn(classes)
  inputs.files(runtimeClasspath)
  val output = layout.buildDirectory.file("runtime-classpath.txt")
  outputs.file(output)
  doLast {
    val jar = classes.get().outputs.files.singleFile
    val entries = listOf(jar) + runtimeClasspath.get().files
    output.get().asFile.writeText(entries.joinToString(":") { it.absolutePath })
  }
}
