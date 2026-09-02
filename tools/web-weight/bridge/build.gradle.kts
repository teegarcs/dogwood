/*
 * Project Dogwood -- what a tree-diff costs to cross the JavaScript/WebAssembly boundary.
 *
 * Deliberately NOT a Compose Multiplatform module. Compose draws through Skiko, which needs a
 * WebGL context that headless Chrome refuses; measuring the bridge needs neither, so dropping
 * both is what makes this harness runnable in a real browser at all. The only dependency is the
 * Kotlin/WebAssembly standard library.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
  jvmToolchain(21)

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    // No `browser {}` webpack bundle. The harness page loads the compiler's own EcmaScript
    // module output directly, because that is the only form in which `@JsExport` functions and
    // the module's exported linear `memory` are reachable from a hand-written page.
    binaries.executable()
    browser()
  }

  sourceSets {
    val wasmJsMain by getting {
      dependencies {}
    }
  }
}
