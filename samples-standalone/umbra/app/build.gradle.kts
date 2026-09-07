/*
 * Umbra's host application: a desktop window that fetches, verifies and renders the payload.
 *
 * Everything below resolves from a repository -- `dogwood-host` for the engine, `:design` for
 * Umbra's own bindings -- and the Zipline compiler plugin comes from Maven Central by coordinate,
 * because there is no version catalog to alias it from outside the Dogwood repository. That line
 * is part of what this sample documents: an adopter writes it too.
 */
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation("dev.dogwood:dogwood-host:0.1.0")
  implementation(project(":design"))
  implementation(compose.desktop.currentOs)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:1.27.0")
}

compose.desktop {
  application {
    mainClass = "dev.umbra.app.MainKt"
    // Interpreted composition is deeply recursive; Zipline expects an eight-megabyte stack.
    jvmArgs += "-Xss8m"
  }
}
