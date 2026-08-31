import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  application
}

kotlin {
  jvmToolchain(21)
  // The experiment driver, the host services, and the report writer are identical on the
  // development host and on device, so they live in one directory that both hosts compile.
  sourceSets["main"].kotlin.srcDir("../host-core/src/main/kotlin")
}

dependencies {
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
