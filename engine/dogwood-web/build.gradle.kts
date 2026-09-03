/*
 * Project Dogwood -- the Web host.
 *
 * Everything the browser profile needs that no other host needs: the `postMessage` bridge to a
 * guest in a Web Worker, the sidecar-manifest loader that runs the dictionary check before that
 * Worker exists, and a minimal Compose Multiplatform renderer for the layout tier.
 *
 * The design is [Layer 5 ADR-032](../../adrs/layer-5/ADR-032-the-web-profile.md).
 *
 * **Why this is not `dogwood-host`.** `dogwood-host` declares `api(libs.zipline)` and
 * `api(libs.zipline.loader)` in its common source set, and Zipline publishes for the Java Virtual
 * Machine, Android and JavaScript -- not for WebAssembly. It also has an `androidTarget`, an
 * `okhttp` dependency and a generated design-system dictionary behind a Java-Virtual-Machine
 * code generator. None of that can be linked into a `wasmJs` compilation as the module stands, so
 * the web host depends on `:dogwood-wire` -- the transport-free protocol -- and rebuilds the small
 * part of the host it actually needs. See this module's README section in the report for what
 * reusing `dogwood-host` properly would take.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin {
  jvmToolchain(21)

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  sourceSets {
    val wasmJsMain by getting {
      dependencies {
        api(project(":dogwood-wire"))
        api(compose.runtime)
        api(compose.foundation)
        api(compose.ui)
        implementation(libs.coroutines.core)
      }
    }
  }
}

/*
 * Incremental compilation is disabled for this module's WebAssembly compilation, and the reason is
 * a compiler defect rather than a preference.
 *
 * Kotlin 2.3.20's incremental Kotlin/WebAssembly path crashes whenever it recompiles a module whose
 * sources changed:
 *
 *     e: java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
 *         at ...ir.backend.js.wasm.WasmIrFileMetadata$Companion.fromByteArray(WasmIrFileMetadata.kt:33)
 *         at ...ir.backend.js.wasm.WasmKlibExportingDeclaration$Companion.collectDeclarations
 *         at ...incremental.IncrementalJsCompilerRunner.runCompiler
 *
 * A clean build always succeeds and every rebuild after an edit fails, which makes the module look
 * broken when it is not -- and `rm -rf build` as a development loop is worse than a slower one.
 *
 * **Why the caches are deleted rather than a flag being set.** The switch that governs this path is
 * `Kotlin2JsCompile.incrementalJsKlib`, which is `internal` to the Kotlin Gradle Plugin and cannot
 * be set by name from a build script. The public `incremental` property is a different switch and
 * does not reach the klib path -- setting it leaves `Using Kotlin/JS incremental compilation` in
 * the log and the crash in place. The `kotlin.incremental.js.klib` Gradle property does work, but
 * it is global: it would disable incremental compilation for every Kotlin/JavaScript module in the
 * engine to work around a defect in one, and it lives in a shared file. Removing this task's own
 * incremental caches before it runs has the same effect with the same scope as the defect.
 *
 * Delete this block when the upstream defect is fixed; the only cost of keeping it is a slower
 * rebuild of one module.
 */
tasks.named("compileKotlinWasmJs").configure {
  doFirst {
    delete(layout.buildDirectory.dir("kotlin/compileKotlinWasmJs"))
  }
}
