import java.math.BigInteger
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.EdECPoint
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec

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
        implementation(project(":dogwood-material3"))
        implementation(project(":dogwood-foundation"))
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

/*
 * The sample's data endpoint, beside the page that serves it.
 *
 * `slice-guest` copies these next to the payload so the mobile hosts can reach them; the web needs
 * its own copy because the page is served from a different directory. The guest is never told this
 * address at compile time -- the page passes its own origin in the launch parameters, exactly as
 * the Android host passes `10.0.2.2` and the iOS host passes `localhost`.
 *
 * Without this the Explore screen's fetch resolves to a 404 and the screen renders its empty state,
 * which is correct behaviour and looks exactly like a network service that does not work.
 */
val copyExploreApi by tasks.registering(Copy::class) {
  from(project(":samples:slice-guest").layout.projectDirectory.dir("api")) {
    include("*.json")
  }
  into(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
}

tasks.named("wasmJsBrowserDistribution") { finalizedBy(copyExploreApi) }

tasks.named("wasmJsBrowserDistribution") {
  // The guest is built as a *dependency* of the distribution and copied as a finalizer. Both on
  // the finalizer deadlocked Gradle -- "items queued for execution but none of them can be
  // started" -- because a finalizer that itself needs another project's webpack task cannot be
  // scheduled against a graph that is already waiting on this one.
  dependsOn(":samples:web-guest:jsBrowserProductionWebpack")
  finalizedBy(copyKotlinGuest)
}

/*
 * Signing the sidecar, which is the web profile's answer to a signed Zipline manifest.
 *
 * ADR-062. The mobile clients verify an Ed25519 signature over the manifest before the loader will
 * run a payload; the web profile had HyperText Transfer Protocol Secure (HTTPS) and nothing else,
 * which authenticates the *server* rather than the *payload*. A detached signature closes it: the
 * manifest keeps its exact bytes and `<name>.json.sig` beside it carries `keyName hexSignature`,
 * one line per key.
 *
 * **Detached rather than embedded, and the reason is canonicalisation.** A signature *inside* the
 * document has to exclude itself from what it covers, which means signer and verifier must agree
 * byte-for-byte on which subset of a JavaScript Object Notation (JSON) document was signed. That
 * kind of disagreement fails silently and late. A detached signature covers the file, whatever is
 * in it, and there is nothing to agree about.
 *
 * Two keys, both throwaway development keys, because the samples sit mid-rotation deliberately --
 * the same posture `slice-guest` takes for the mobile manifest and for the same reason: a rotation
 * that is only ever described is a rotation nobody has performed.
 */
val signWebSidecars by tasks.registering {
  description = "Signs each sidecar manifest with a detached Ed25519 signature."
  val distribution = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
  val trustFile = rootProject.file("dogwood-wire/src/commonMain/kotlin/dev/dogwood/protocol/Trust.kt")
  inputs.file(trustFile)
  outputs.dir(distribution)
  // Never up to date: the address and the digest it stamps are of whatever guest script is in the
  // distribution right now, and the skew drill swaps that file in without Gradle's knowledge.
  outputs.upToDateWhen { false }
  doLast {
    /*
     * The SEEDS below are THROWAWAY DEVELOPMENT KEYS, committed on purpose so the sample builds for
     * anyone who clones this. They sign nothing anyone should trust. They are the same two seeds
     * `samples/slice-guest/build.gradle.kts` uses for the mobile manifest, which is what makes the
     * web and mobile samples mid-rotation in the same way rather than in two different ways.
     *
     * A real signing key never lives in a repository. Pass `-PdogwoodSigningKey=<hex>` and
     * `-PdogwoodRotationKey=<hex>`, or set them in `~/.gradle/gradle.properties`.
     */
    val seeds = linkedMapOf(
      "dogwood-development" to
        (providers.gradleProperty("dogwoodSigningKey").orNull
          ?: "0ca845610dac5a568230ae0b4468004a787b5a541603554a0d3903535dd1f742"),
      "dogwood-development-2" to
        (providers.gradleProperty("dogwoodRotationKey").orNull
          ?: "de597b577357a748f319fcd06ddb4994f58f487be0d2118a4dc08e44e4b61862"),
    )

    /*
     * The public keys are READ from the trust anchor the hosts compile in, never restated here.
     *
     * This is the check that makes the seeds above safe to commit as a pair with those keys: sign
     * with the private seed, verify with the public key the clients actually trust, and fail the
     * build if they do not match. A signer and a verifier that disagree produce a page that refuses
     * every payload, and the symptom -- "the signature does not verify" -- looks like an attack.
     */
    val trust = trustFile.readText()
    val publicKeys = Regex("""DEVELOPMENT(?:_2)? to "([0-9a-f]{64})"""")
      .findAll(trust).map { it.groupValues[1] }.toList()
    require(publicKeys.size == seeds.size) {
      "expected ${seeds.size} development public keys in Trust.kt, found ${publicKeys.size}"
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(text: String) = ByteArray(text.length / 2) {
      text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    val factory = KeyFactory.getInstance("Ed25519")

    /*
     * A raw 32-byte Ed25519 public key is little-endian `y` with the top bit of the last byte
     * carrying the sign of `x` (RFC 8032 §5.1.2). The Java Development Kit wants that decomposed
     * into an `EdECPoint`, so this does the decomposition rather than assuming a codec exists.
     */
    fun publicKeyFromRaw(raw: ByteArray): PublicKey {
      val bytes = raw.copyOf()
      val xOdd = (bytes[31].toInt() and 0x80) != 0
      bytes[31] = (bytes[31].toInt() and 0x7F).toByte()
      val y = BigInteger(1, bytes.reversedArray())
      return factory.generatePublic(
        EdECPublicKeySpec(
          NamedParameterSpec.ED25519,
          EdECPoint(xOdd, y),
        ),
      )
    }

    val signers = seeds.entries.mapIndexed { index, (name, seed) ->
      val private = factory.generatePrivate(
        EdECPrivateKeySpec(
          NamedParameterSpec.ED25519,
          unhex(seed),
        ),
      )
      Triple(name, private, publicKeyFromRaw(unhex(publicKeys[index])))
    }

    val directory = distribution.get().asFile
    val manifests = directory.listFiles { file -> file.name.matches(Regex("""dogwood-manifest.*\.json""")) }
      ?.sortedBy { it.name }
      .orEmpty()
    require(manifests.isNotEmpty()) { "no sidecar manifests in $directory to sign" }

    /*
     * The script digest, filled in from the built script BEFORE signing, so the signature covers it.
     *
     * Read out of the manifest's own `guestScript` field rather than assumed, because the sample
     * has two guests (a hand-written JavaScript one and the Kotlin one) and a fixture that names the
     * wrong one is exactly what the signature drill uses. One fixture keeps its digest on purpose:
     * `dogwood-manifest-tampered-script.json` carries a digest that is wrong by construction, and
     * the point of it is that its signature is *valid* over that wrong digest -- a script swapped
     * after signing, seen from the client. Its *address* is rewritten like every other sidecar's,
     * because a fixture that names a file the distribution does not have would be refused for the
     * 404 rather than for the digest.
     */
    /*
     * The dictionary a sidecar declares, stamped from the generators' own output before signing.
     *
     * `"segmentVersions": null` in a committed fixture is a request to be filled in. A fixture that
     * spells the versions out keeps them, because the refusal fixtures exist precisely to name
     * numbers no client has.
     *
     * A committed number goes stale silently, and this repository has the receipt:
     * `dogwood-manifest-kotlin.json` named design-system version 9 for weeks after the surface
     * reached 15, so the one sidecar meant to model a healthy payload was describing a payload
     * nobody had built. The mobile payload has read its declaration from the generators since the
     * first time that bit it (`samples/slice-guest/build.gradle.kts`); this is the web's half.
     */
    val builtIns = rootProject.file("build/generated/dogwood/wire/dev/dogwood/protocol/DogwoodSegments.kt")
    val tierDictionary = rootProject.file("build/generated/dogwood-material3/dictionary/androidx.material3.json")
    val declaredVersions: String by lazy {
      require(builtIns.isFile) { "the built-in segment vector has not been generated: $builtIns" }
      require(tierDictionary.isFile) { "the Material 3 tier's dictionary has not been generated: $tierDictionary" }
      val vector = builtIns.readText()
      fun builtIn(prefix: String): Pair<String, String> {
        val name = Regex("""const val $prefix: String = "([^"]+)"""").find(vector)?.groupValues?.get(1)
          ?: error("no $prefix in $builtIns")
        val version = Regex("""const val ${prefix}_VERSION: Int = (\d+)""").find(vector)?.groupValues?.get(1)
          ?: error("no ${prefix}_VERSION in $builtIns")
        return name to version
      }
      val tier = tierDictionary.readText()
      val tierName = Regex(""""wireName"\s*:\s*"([^"]+)"""").find(tier)?.groupValues?.get(1)
        ?: error("no wireName in $tierDictionary")
      val tierVersion = Regex(""""version"\s*:\s*(\d+)""").find(tier)?.groupValues?.get(1)
        ?: error("no version in $tierDictionary")
      listOf(builtIn("LAYOUT"), builtIn("DESIGN_SYSTEM"), tierName to tierVersion)
        .joinToString(", ", "{ ", " }") { (name, version) -> "\"$name\": $version" }
    }

    val digests = MessageDigest.getInstance("SHA-256")

    /*
     * The guest script's ADDRESS names its own bytes, written into every sidecar before signing.
     *
     * ADR-078, and the web half of ADR-077. Every release this sample has ever produced named its
     * payload `guest-kotlin.js` -- the webpack output name, and nothing varies it. That is
     * invisible while one release is live and is a defect the moment two are: `WebDelivery`
     * resolves `guestScript` against the sidecar's own address, and a canary is served by
     * returning different sidecar *content* at one sidecar address (`tools/reference-server`,
     * ADR-049), so both releases' scripts resolve to the same absolute URL and only one file can
     * be there. A client holding the other release's signed sidecar fetches the wrong bytes and
     * refuses on the digest. Watched, in a real browser, before this was written:
     *
     *     the bytes at http://127.0.0.1:8931/guest-kotlin.js hash to 2bda3a2c...;
     *     the signed manifest says ad168810...
     *
     * The integrity check catching it is the right direction -- a refused load rather than a wrong
     * screen -- but a refused load is a page that cannot start, and which visitors it happens to is
     * decided by which cohort somebody pinned.
     *
     * **Why the build and not the deployment.** `guestScript` is inside the signed region: the
     * detached signature below covers the manifest's whole bytes, and `WebDelivery.verifySidecar`
     * verifies over the whole document it fetched. A deployment that renamed the script and
     * rewrote the address would invalidate the signature, and every client holding keys would
     * refuse. That is not inferred -- it is claim `B1`, which alters exactly this field in the
     * build's own signed output and grades the refusal in a browser. So the address has to be
     * written before the signature, which is here.
     *
     * Sixteen hexadecimal digits of the SHA-256, matching
     * `engine/gradle/content-addressed-modules.gradle.kts` so the two profiles address payloads the
     * same way. The full digest is still carried in `guestScriptSha256` and still checked, so a
     * collision would cost a refused load rather than wrong code.
     *
     * Idempotent, because it has to be: the task is `upToDateWhen { false }` and
     * `tools/skew-drill/run-web.sh` swaps a script in behind Gradle's back and runs it again.
     */
    val guestBase = "guest-kotlin"
    val guestExtension = "js"
    val addressed = Regex(
      "^" + Regex.escape(guestBase) + "-[0-9a-f]{16}\\." + Regex.escape(guestExtension) + "$",
    )
    val plainGuest = File(directory, "$guestBase.$guestExtension")
    val alreadyAddressed = directory.listFiles { file: File -> addressed.matches(file.name) }
      ?.sortedBy { it.name }.orEmpty()
    /*
     * The freshly copied plain file wins when it is there, because that is what `copyKotlinGuest`
     * -- and the skew drill's `cp` -- just put down. Falling back to an existing address covers a
     * re-run over a directory this task already addressed. Neither present is not an error here:
     * the sidecars that name no Kotlin guest are still perfectly signable, and a sidecar that does
     * name one runs into the `require` below with a message about the file rather than about a
     * pattern.
     */
    val guestSource = when {
      plainGuest.isFile -> plainGuest
      alreadyAddressed.size == 1 -> alreadyAddressed.single()
      alreadyAddressed.size > 1 -> error(
        "$directory holds ${alreadyAddressed.size} content-addressed guest scripts " +
          "(${alreadyAddressed.joinToString { it.name }}) and no plain $guestBase.$guestExtension, " +
          "so there is no way to tell which one this distribution ships",
      )
      else -> null
    }
    val guestAddress = guestSource?.let { source ->
      val address = "$guestBase-${hex(digests.digest(source.readBytes())).take(16)}.$guestExtension"
      if (source.name != address) {
        source.copyTo(File(directory, address), overwrite = true)
        if (source == plainGuest) source.delete()
      }
      /*
       * Earlier addresses of this same script are deleted rather than left behind. They are stale
       * bytes under an address that is supposed to be immutable, and a distribution that
       * accumulated them would serve payloads nothing can ever ask for.
       */
      directory.listFiles()?.forEach { file ->
        if (file.name != address && addressed.matches(file.name)) file.delete()
      }
      address
    }

    for (manifest in manifests) {
      var text = manifest.readText()

      /*
       * The address goes into EVERY sidecar, the integrity fixture included. That fixture is
       * skipped below so its deliberately wrong digest survives, but an address it cannot resolve
       * would turn it into a 404 -- `ManifestUnavailable` instead of `IntegrityRefused` -- and
       * claim `B5` would be grading a missing file rather than a swapped script. What that fixture
       * models is a script swapped at the origin *after* signing, and it can only model it while
       * the script it names is actually there.
       */
      if (guestAddress != null) {
        text = Regex(""""guestScript"\s*:\s*"([^"]+)"""").replace(text) { match ->
          val named = match.groupValues[1]
          if (named == "$guestBase.$guestExtension" || addressed.matches(named)) {
            "\"guestScript\": \"$guestAddress\""
          } else {
            match.value
          }
        }
      }

      if (!manifest.name.contains("tampered-script")) {
        text = Regex(""""segmentVersions"\s*:\s*null""")
          .replace(text) { "\"segmentVersions\": $declaredVersions" }
        val script = Regex(""""guestScript"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
          ?: error("${manifest.name} names no guestScript")
        val scriptFile = File(directory, script)
        require(scriptFile.isFile) { "${manifest.name} names $script, which is not in $directory" }
        val digest = hex(digests.digest(scriptFile.readBytes()))
        val stamped = Regex(""""guestScriptSha256"\s*:\s*(null|"[^"]*")""")
          .replace(text) { "\"guestScriptSha256\": \"$digest\"" }
        require(stamped != text || text.contains("\"$digest\"")) {
          "${manifest.name} has no guestScriptSha256 field to fill in; add `\"guestScriptSha256\": null`"
        }
        text = stamped
      }
      manifest.writeText(text)
      val bytes = manifest.readBytes()
      val lines = signers.map { (name, private, public) ->
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(private)
        signer.update(bytes)
        val signature = signer.sign()

        // Verified here, against the key the CLIENTS hold, before it is written. A signature this
        // build produces and no client can check is worse than no signature: it looks like
        // protection and refuses every load.
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(public)
        verifier.update(bytes)
        check(verifier.verify(signature)) {
          "the seed for `$name` does not match the public key in Trust.kt; the sample's committed " +
            "development key pair has drifted"
        }
        "$name ${hex(signature)}"
      }
      File(directory, "${manifest.name}.sig").writeText(
        "# Detached Ed25519 signatures over ${manifest.name}, produced by :samples:web-slice:signWebSidecars.\n" +
          "# THROWAWAY DEVELOPMENT KEYS. See the task in build.gradle.kts.\n" +
          lines.joinToString("\n") + "\n",
      )
    }
    logger.lifecycle("signed ${manifests.size} sidecar manifests with ${signers.size} development keys")
  }
}

tasks.named("wasmJsBrowserDistribution") { finalizedBy(signWebSidecars) }
// The digest is of the script the distribution actually ships, so the Kotlin guest has to have
// been copied in before the manifests are stamped and signed.
signWebSidecars.configure { mustRunAfter(tasks.named("copyKotlinGuest")) }
