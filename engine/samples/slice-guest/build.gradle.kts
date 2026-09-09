plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.zipline)
}

kotlin {
  jvmToolchain(21)
  js(IR) {
    browser()
    binaries.executable()
  }
  sourceSets {
    jsMain {
      dependencies {
        implementation(project(":dogwood-compose"))
        // The screens, which this module used to contain. `slice-guest` is now only an entry
        // point: it binds them to a Zipline service and nothing else.
        implementation(project(":samples:slice-screens"))
      }
    }
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":samples:product-design-system:generateAcme")
}

/*
 * The manifest is signed, and Layer 3 will not load an unsigned one.
 *
 * The default below is a THROWAWAY DEVELOPMENT KEY, committed on purpose so the slice builds
 * for anyone who clones this. It signs nothing anyone should trust. A real signing key never
 * lives in a repository: pass `-PdogwoodSigningKey=<hex>` or set it in `~/.gradle/gradle.properties`,
 * and rotate the public key in `SliceActivity` to match. Layer 3's key-rotation drill --
 * ship a manifest with two signatures, roll clients forward, retire the old key -- is exercised
 * here, and both keys below are throwaway development keys for exactly that reason.
 */
val developmentSigningKey = "0ca845610dac5a568230ae0b4468004a787b5a541603554a0d3903535dd1f742"

/*
 * The key being rotated TO, and the reason there are two.
 *
 * A rotation only works if a manifest can be signed by the old key and the new one at once, so
 * that clients holding either can verify it while the fleet rolls forward. Zipline supports it by
 * skipping signatures whose key name it does not recognise and requiring the first name it *does*
 * recognise to verify -- which means the order of these entries is part of the contract, not a
 * detail. `KeyRotationTest` pins the whole sequence against this real manifest.
 *
 * Retiring the old key is then deleting its entry here and shipping. A client still holding only
 * the old key stops accepting updates at that moment, which is what makes the roll-forward step
 * something to finish rather than start.
 */
val rotationSigningKey = "de597b577357a748f319fcd06ddb4994f58f487be0d2118a4dc08e44e4b61862"

/**
 * The dictionary this payload was built against, as the `name:version` pairs a client compares
 * before it agrees to run any of this.
 *
 * **Read out of the generator's own output, never restated here.** `DogwoodSegments.kt` and each
 * product's dictionary JavaScript Object Notation (JSON) file are written by `dogwood-codegen` from
 * the same surfaces this payload compiled against, so a surface change that raises a version cannot
 * leave the manifest describing a payload that no longer exists. A hand-typed number in this file
 * would be a second copy of the truth, and the failure it would cause -- a manifest claiming a
 * version the payload does not have -- is precisely the failure the declaration exists to prevent,
 * arriving through the declaration itself. The project has already been bitten once by a hand-typed
 * `6` disagreeing with a lock that said `7`.
 *
 * Every segment the payload can name is listed, including Acme's: `slice-screens` composes
 * `AcmePanel`, `AcmePrice` and `AcmeAction`, so a client with no Acme binding registered genuinely
 * cannot render this payload, and saying so is the whole point.
 *
 * **A `Provider`, and it must stay one.** The generated files this reads are written by tasks in
 * this same build, so resolving the value while Gradle is configuring reads whatever the *previous*
 * build left behind. That is not hypothetical: the first version of this called `.get()` inside
 * `metadata.set(...)`, a skew drill moved the design system to version 15, and the manifest went
 * out declaring 14 -- describing a payload that no longer existed, which is the exact failure the
 * declaration exists to prevent. Left as a provider, the whole map resolves when the manifest task
 * runs, after generation.
 */
val declaredSegments: Provider<String> = providers.provider {
  fun read(file: File, what: String): String =
    if (file.exists()) file.readText() else error("$what has not been generated yet: $file")

  val builtIns = read(
    rootProject.file("build/generated/dogwood/wire/dev/dogwood/protocol/DogwoodSegments.kt"),
    "the built-in segment vector",
  )
  fun builtIn(prefix: String): String {
    val name = Regex("""const val $prefix: String = "([^"]+)"""").find(builtIns)?.groupValues?.get(1)
    val version = Regex("""const val ${prefix}_VERSION: Int = (\d+)""").find(builtIns)?.groupValues?.get(1)
    return (name ?: error("no " + prefix + " in the generated vector")) + ":" +
      (version ?: error("no " + prefix + "_VERSION in the generated vector"))
  }

  val acme = read(
    rootProject.file(
      "samples/product-design-system/build/generated/acme/dictionary/acme.designsystem.json",
    ),
    "Acme's dictionary",
  )
  val acmeName = Regex(""""wireName"\s*:\s*"([^"]+)"""").find(acme)?.groupValues?.get(1)
  val acmeVersion = Regex(""""version"\s*:\s*(\d+)""").find(acme)?.groupValues?.get(1)

  listOf(
    builtIn("LAYOUT"),
    builtIn("DESIGN_SYSTEM"),
    (acmeName ?: error("no wireName in Acme's dictionary")) + ":" +
      (acmeVersion ?: error("no version in Acme's dictionary")),
  ).joinToString(",")
}

zipline {
  mainFunction.set("dev.dogwood.slice.main")
  optimizeForSmallArtifactSize()
  version.set("1.0.0")
  // The kill switch, and it is a publisher's control rather than a developer's. Setting it to
  // "true" and republishing stops devices running this release without waiting for them to
  // discover it is broken. It rides in the manifest's **signed** metadata: an attacker who could
  // set it in an unsigned field could disable an application through the very channel that exists
  // to secure its updates.
  metadata.set(
    providers.gradleProperty("dogwoodDisabled").map { mapOf("dogwood.disabled" to it) }
      .getOrElse(emptyMap()),
  )
  // What dictionary this payload was built against, so a client can refuse it **before** running it
  // rather than degrading through it (audit A3's mobile counterpart; the web profile has had this
  // since ADR-032). Signed, like the kill switch above it: a field an attacker could set unsigned
  // would be a denial of service through the update channel.
  //
  // `putAll(provider)` rather than a second `set`, because `set` replaces and the kill switch is
  // already in there.
  //
  // `-PdogwoodDeclareSegments=false` publishes a payload that declares nothing, and the skew drill
  // uses it. Not a debug switch: a payload that declares nothing is the ordinary case for
  // everything built before this field existed, and render-time containment -- placeholders,
  // withheld affordances, reported skew -- is what protects those. The containment drill has to be
  // able to produce one, or the rules it grades would become untestable the moment the declaration
  // was added. See `tools/skew-drill/README.md`.
  metadata.putAll(
    declaredSegments.map { segments ->
      if (providers.gradleProperty("dogwoodDeclareSegments").getOrElse("true") == "false") {
        emptyMap()
      } else {
        mapOf("dogwood.segments" to segments)
      }
    },
  )
  signingKeys {
    create("dogwood-development") {
      privateKeyHex.set(
        providers.gradleProperty("dogwoodSigningKey").getOrElse(developmentSigningKey),
      )
      algorithmId.set(app.cash.zipline.loader.SignatureAlgorithmId.Ed25519)
    }
    // The rotation target. A client that has been rolled forward trusts this one; a client that
    // has not still verifies against the entry above. Both signatures ride the same manifest.
    create("dogwood-development-2") {
      privateKeyHex.set(
        providers.gradleProperty("dogwoodRotationKey").getOrElse(rotationSigningKey),
      )
      algorithmId.set(app.cash.zipline.loader.SignatureAlgorithmId.Ed25519)
    }
  }
}

/*
 * The sample's data endpoint, served from the same development server as the payload.
 *
 * A real deployment's feed comes from a real service; this exists so the network path is
 * exercised end to end without inventing one. The guest is never told this address at compile
 * time -- the host passes it in the launch parameters, because `10.0.2.2` on an Android emulator
 * and `localhost` on a desktop are the same machine reached by different names, and only the host
 * knows which it is.
 */
val copyExploreApi by tasks.registering(Copy::class) {
  from(layout.projectDirectory.file("api/explore.json"))
  from(layout.projectDirectory.file("api/theme-ocean.json"))
  from(layout.projectDirectory.file("api/theme-sunset.json"))
  into(layout.buildDirectory.dir("zipline/ProductionWebpack"))
}

tasks.matching { it.name == "jsBrowserProductionWebpackZipline" }.configureEach {
  finalizedBy(copyExploreApi)
}
tasks.matching { it.name == "serveProductionWebpackZipline" }.configureEach {
  dependsOn(copyExploreApi)
}

/*
 * The authoring check Layer 1 requires.
 *
 * Invoked as a command rather than through the `dev.dogwood.guest` plugin, for the reason the
 * generator is invoked as one here too: the plugin lives in this build, and applying it by
 * identifier inside the build that defines it would need a composite build or a prior publish. A
 * product applies the plugin and never sees this shape.
 */
val dogwoodGuestCheck by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Rejects guest code that would tick the boundary every frame"
  classpath = project(":dogwood-codegen").sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.guest.GuestCheckKt")
  val sources = project.file("src")
  inputs.dir(sources).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.upToDateWhen { false }
  argumentProviders.add { listOf(sources.absolutePath) }
}

tasks.named("check") { dependsOn(dogwoodGuestCheck) }
