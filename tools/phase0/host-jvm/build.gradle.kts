import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  application
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(project(":host-core"))
  implementation(project(":protocol"))
  implementation(libs.zipline)
  implementation(libs.zipline.loader)
  implementation(libs.coroutines.core)
  implementation(libs.serialization.json)
  implementation(libs.okio)

  // The Zipline Kotlin compiler plugin rewrites `take`/`bind` into adapter calls. The
  // `app.cash.zipline` Gradle plugin only wires this up for Kotlin Multiplatform projects,
  // so a Java Virtual Machine (JVM)-only module adds it by hand -- exactly as Zipline's own
  // `samples/trivia/trivia-host` does.
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}

application {
  mainClass.set("dev.dogwood.host.MainKt")
  applicationDefaultJvmArgs = listOf("-Xss8m")
}

tasks.named<JavaExec>("run") {
  // Resolve the guest artifacts relative to the harness root, not this module.
  workingDir = rootProject.projectDir
  dependsOn(":guest:jsBrowserProductionWebpackZipline")
}

/** Prints the bytes that actually cross the boundary. See DumpWire.kt. */
tasks.register<JavaExec>("dumpWire") {
  group = "verification"
  description = "Capture and analyse the real wire payload"
  mainClass.set("dev.dogwood.host.DumpWireKt")
  classpath = sourceSets["main"].runtimeClasspath
  jvmArgs("-Xss8m")
  workingDir = rootProject.projectDir
  dependsOn(":guest:jsBrowserProductionWebpackZipline")
}

/** Experiment 0.5: allocation and garbage collection. See AllocGcMain.kt. */
tasks.register<JavaExec>("allocGc") {
  group = "verification"
  description = "Measure allocation per batch and the tail of a sustained load"
  mainClass.set("dev.dogwood.host.AllocGcMainKt")
  classpath = sourceSets["main"].runtimeClasspath
  jvmArgs("-Xss8m")
  workingDir = rootProject.projectDir
  dependsOn(":guest:jsBrowserProductionWebpackZipline")
}
