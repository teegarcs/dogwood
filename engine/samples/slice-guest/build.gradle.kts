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
    }
  }
}

/*
 * The manifest is signed, and Layer 3 will not load an unsigned one.
 *
 * The default below is a THROWAWAY DEVELOPMENT KEY, committed on purpose so the slice builds
 * for anyone who clones this. It signs nothing anyone should trust. A real signing key never
 * lives in a repository: pass `-PdogwoodSigningKey=<hex>` or set it in `~/.gradle/gradle.properties`,
 * and rotate the public key in `SliceActivity` to match. Layer 3's key-rotation drill --
 * ship a manifest with two signatures, roll clients forward, retire the old key -- is a Phase 5
 * hardening item and is not exercised here.
 */
val developmentSigningKey = "0ca845610dac5a568230ae0b4468004a787b5a541603554a0d3903535dd1f742"

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
  into(layout.buildDirectory.dir("zipline/ProductionWebpack"))
}

tasks.matching { it.name == "jsBrowserProductionWebpackZipline" }.configureEach {
  finalizedBy(copyExploreApi)
}
tasks.matching { it.name == "serveProductionWebpackZipline" }.configureEach {
  dependsOn(copyExploreApi)
}
