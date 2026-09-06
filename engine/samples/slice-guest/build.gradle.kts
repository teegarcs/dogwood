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
      }
      /*
       * Acme's generated guest stubs.
       *
       * A real product would publish these as a Kotlin/JavaScript library and depend on it, the
       * same way it depends on `dogwood-compose`. This is a source directory across a module
       * boundary because both halves live in one repository and publishing a sample to a
       * repository to consume it back would prove less, not more.
       *
       * What it does prove is the part that matters: the guest calls `AcmePrice(...)` and gets
       * `dev.acme.guest`'s stub, which records a widget in **segment 2** and knows nothing about
       * Dogwood's own components.
       */
      kotlin.srcDir(
        project(":samples:product-design-system").layout.buildDirectory.dir("generated/acme/guest"),
      )
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

zipline {
  mainFunction.set("dev.dogwood.slice.main")
  optimizeForSmallArtifactSize()
  version.set("1.0.0")
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
