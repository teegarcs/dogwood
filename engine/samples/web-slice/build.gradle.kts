/*
 * Project Dogwood -- the web slice: a page, a guest in a Worker, and a rendered tree.
 *
 * The smallest thing that is genuinely end to end. It is not a smaller `slice-android`: it links
 * five widget bindings rather than nineteen, and its guest is thirty lines of hand-written
 * JavaScript rather than a compiled Kotlin composition. What it does share with the real slice is
 * every byte of the wire format and every step of the sequence -- sidecar check, Worker, envelope,
 * positional batch, applier, Compose.
 *
 * See `run.sh` for how it is built and verified.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin {
  jvmToolchain(21)

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser {
      commonWebpackConfig { outputFileName = "app.js" }
    }
    binaries.executable()
  }

  sourceSets {
    val wasmJsMain by getting {
      dependencies {
        implementation(project(":dogwood-web"))
        // Acme's design system, so a product's own components render here too. Registered in
        // `main`, exactly as the Android host registers it in `Application.onCreate`.
        implementation(project(":samples:product-design-system"))
      }
    }
  }
}

/*
 * The `--gufa` removal, and the build-time half of the correctness gate.
 *
 * Kotlin 2.3.20 finishes a production WebAssembly build by running Binaryen's `wasm-opt` with the
 * pass list in `org.jetbrains.kotlin.gradle.internal.platform.wasm.BinaryenConfig`, which contains
 * `--gufa`. Under `--closed-world` that pass does not model an imported builtin as being able to
 * mutate a WebAssembly garbage-collected object, so it concludes that the array
 * `String.toCharArray()` fills through the `wasm:js-string` `intoCharCodeArray` import only ever
 * holds its default value -- and the function silently returns the right number of zeros.
 * [ADR-032](../../../adrs/layer-5/ADR-032-the-web-profile.md) removes the pass; this is where.
 *
 * `binaryenArgs` is a plain settable `List<String>` on `BinaryenExec`, so the removal is a filter
 * rather than a re-declaration of the whole list. That matters: re-declaring it would silently
 * freeze the pass list at Kotlin 2.3.20's, and a later Kotlin whose list gained a pass we *want*
 * would be quietly ignored.
 *
 * The `check` is not decoration either. A Kotlin upgrade that reordered or renamed the pass would
 * make the filter match nothing, the build would keep succeeding, and the only symptom would be a
 * host that decodes every batch as empty. The runtime half of the gate -- `BulkCopyGate`, which
 * compares the fast decoder against the reference decoder inside the shipped binary -- catches the
 * same defect from the other end, and `run.sh` fails if the page reports it.
 */
tasks.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec>().configureEach {
  val without = binaryenArgs.filterNot { it == "--gufa" }
  // Asserts the OUTCOME, not a disjunction that cannot be false. The previous form was
  // `without.size < binaryenArgs.size || !binaryenArgs.contains("--gufa")` -- true whenever the
  // list contains the pass, true whenever it does not, and therefore never a check at all, under
  // a comment insisting it was "not decoration".
  check(!without.contains("--gufa")) {
    "the wasm-opt pass list still contains --gufa after filtering; see ADR-032"
  }
  binaryenArgs = without.toMutableList()
  doFirst {
    check(!binaryenArgs.contains("--gufa")) {
      "--gufa is present in the wasm-opt pass list. Kotlin 2.3.20 miscompiles " +
        "String.toCharArray() under it, which makes every Dogwood batch decode as empty. " +
        "See adrs/layer-5/ADR-032-the-web-profile.md."
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

/*
 * Preload the WebAssembly chunks, and why it is worth a build step.
 *
 * The shipped page is `<script src="app.js">`. The browser therefore learns the two `.wasm` URLs
 * only after `app.js` has been fetched, decompressed and parsed — so the 2.6 MB renderer, which is
 * three quarters of the page, cannot start downloading until a smaller file has finished. On a link
 * with a 562 ms round trip that serialisation is not free.
 *
 * `<link rel="preload">` in the head starts both fetches immediately. Measured with
 * `tools/web-ttff` ([ADR-045](../../../adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)),
 * it is the second lever that pays and the largest *relative* one: **17% off the first frame on
 * 5G**, and about six tenths of a second on Fast 3G. It moves no bytes at all.
 *
 * It has to be a build step rather than two lines in `index.html` because the filenames are
 * content-hashed — which is also what makes them cacheable, so the two facts are the same fact.
 *
 * `as="fetch"` rather than `as="script"`: the modules are instantiated by the Kotlin glue through
 * `WebAssembly.instantiateStreaming` over a `fetch`, so that is the request the hint has to match.
 * A mismatched `as` is worse than no hint — the browser downloads the file twice and warns in a
 * console nobody reads in production.
 */
val preloadWasm by tasks.registering {
  description = "Adds <link rel=preload> for each WebAssembly chunk to the distribution's index.html"
  val dist = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
  outputs.upToDateWhen { false }
  doLast {
    val dir = dist.get().asFile
    val page = File(dir, "index.html")
    if (!page.isFile) return@doLast
    val existing = page.readText()
    // Idempotent: the distribution directory is not always cleaned between builds, and a page with
    // the hints applied twice would fetch each chunk twice.
    if ("rel=\"preload\"" in existing) return@doLast
    val chunks = dir.listFiles { f: File -> f.name.endsWith(".wasm") }?.sortedBy { it.name }.orEmpty()
    check(chunks.isNotEmpty()) { "no .wasm chunks in $dir; the distribution did not build" }
    val links = chunks.joinToString("\n") {
      """  <link rel="preload" href="${it.name}" as="fetch" type="application/wasm" crossorigin>"""
    }
    page.writeText(existing.replace("</head>", "$links\n</head>"))
    logger.lifecycle("web-slice: preloading ${chunks.size} WebAssembly chunks")
  }
}

tasks.named("wasmJsBrowserDistribution") { finalizedBy(preloadWasm) }

/*
 * The real guest's Worker bundle, copied into this page's distribution.
 *
 * `samples/web-guest` compiles the same screens the mobile payload runs — `samples/slice-screens`,
 * which names no transport — into a script a `new Worker(...)` can load. It is copied rather than
 * depended on, because the host is Kotlin/WebAssembly and the guest is Kotlin/JavaScript: they do
 * not link, and the *only* thing they share is the protocol. Copying a script into a directory is
 * an honest expression of that; a Gradle dependency would not be.
 */
val copyKotlinGuest by tasks.registering(Copy::class) {
  from(project(":samples:web-guest").layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) {
    include("guest-kotlin.js")
  }
  into(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
}

tasks.named("wasmJsBrowserDistribution") {
  // The guest is built as a *dependency* of the distribution and copied as a finalizer. Both on
  // the finalizer deadlocked Gradle -- "items queued for execution but none of them can be
  // started" -- because a finalizer that itself needs another project's webpack task cannot be
  // scheduled against a graph that is already waiting on this one.
  dependsOn(":samples:web-guest:jsBrowserProductionWebpack")
  finalizedBy(copyKotlinGuest)
}
