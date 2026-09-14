/*
 * Umbra on the web -- the host, as a page outside the engine would build it.
 *
 * Until 2026-09-13 `dogwood-web` was the one engine module with no publishing coordinates at all:
 * the web host existed, rendered, passed its conformance drill, and could not be depended on by any
 * build that was not the engine's own. This module is what asserts that it can. It resolves
 * `dev.dogwood:dogwood-web` from a repository in a Kotlin/WebAssembly build and compiles a page
 * against it -- the delivery, the experience, and the registration of Umbra's own design system,
 * which is the shape every page has.
 *
 * **What this module proves, and what it does not.** The artifact exists, is selected as the
 * WebAssembly variant rather than something Gradle fell through to, and its API is reachable from a
 * product's own design system compiled for the same target. It does **not** render: there is no
 * browser in this check and the verdict is a compile. The render is covered by the engine's own web
 * conformance drill (`tools/conformance/run-web.sh`); what that drill cannot cover is *consumption*,
 * and consumption is the whole of what was missing.
 */
plugins {
  kotlin("multiplatform")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

kotlin {
  jvmToolchain(21)

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { browser() }

  sourceSets {
    val wasmJsMain by getting {
      dependencies {
        // Resolved from a repository by a WebAssembly build, with no path into the engine. That
        // sentence is the whole test.
        implementation("dev.dogwood:dogwood-web:0.1.0")
        // The product's own design system, compiled for the same target: the web page calls the
        // same generated binding the Android and iOS applications do.
        implementation(project(":design"))
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      }
    }
  }
}
