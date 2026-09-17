/*
 * Project Dogwood -- a second payload, owned by a second team.
 *
 * The point is not what it draws. It is that this is a **separately built, separately signed,
 * separately versioned** payload from `slice-guest`, so that `samples/two-payloads` can host two of
 * them at once and the cost of doing so can be measured rather than estimated
 * (`plans/adoption-audit.md` B3, `docs/multi-team.md` §2).
 *
 * It is deliberately small. A second payload the size of the first would measure the first payload
 * twice; a small one isolates the cost that is *per shell* -- an interpreter, a cache, a guard, a
 * dispatcher -- from the cost that is per screen.
 */
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
 * The SAME throwaway development key `slice-guest` uses, and that is a decision rather than a
 * shortcut.
 *
 * Two teams shipping independently do not each get their own trust anchor: the key is the *client's*
 * -- it is compiled into the application binary, and a second key would have to be compiled in
 * alongside it and rotated on the same schedule. What separates the teams is the manifest they
 * publish and the release identity in it, not the signature. A product that genuinely wants
 * per-team keys can pass both to `DogwoodDelivery`; nothing here prevents it, and the cost is that
 * key rotation becomes two rotations.
 */
val developmentSigningKey = "0ca845610dac5a568230ae0b4468004a787b5a541603554a0d3903535dd1f742"

zipline {
  mainFunction.set("dev.dogwood.second.main")
  optimizeForSmallArtifactSize()
  version.set(providers.gradleProperty("secondVersion").getOrElse("2.0.0"))
  signingKeys {
    create("dogwood-development") {
      privateKeyHex.set(
        providers.gradleProperty("dogwoodSigningKey").getOrElse(developmentSigningKey),
      )
      algorithmId.set(app.cash.zipline.loader.SignatureAlgorithmId.Ed25519)
    }
  }
  // Declares the dictionary the engine emits, for the same reason `slice-guest` does (ADR-061).
  // Read from the generator's output, never restated, and resolved as a provider so it reads this
  // build's generated files rather than the previous build's.
  metadata.putAll(
    providers.provider {
      val vector = rootProject
        .file("build/generated/dogwood/wire/dev/dogwood/protocol/DogwoodSegments.kt")
        .readText()
      fun entry(prefix: String): String {
        val name = Regex("""const val $prefix: String = "([^"]+)"""").find(vector)!!.groupValues[1]
        val version = Regex("""const val ${prefix}_VERSION: Int = (\d+)""").find(vector)!!.groupValues[1]
        return name + ":" + version
      }
      mapOf("dogwood.segments" to listOf(entry("LAYOUT"), entry("DESIGN_SYSTEM")).joinToString(","))
    },
  )
}

/*
 * Each module renamed for its own content, and the manifest re-signed over the new name.
 *
 * The same step `slice-guest` applies and for the same reason (ADR-077): an address that is the
 * same string in every release leaves a module request with nothing to say which release it wants.
 * This payload is only ever served one release at a time today, and it gets the step anyway --
 * a property that holds only where somebody remembered to arrange it is not a property.
 *
 * One key here rather than two, matching the `signingKeys` block above: this payload is not part of
 * the rotation drill.
 */
extra["dogwoodSigningKeys"] = linkedMapOf(
  "dogwood-development" to
    providers.gradleProperty("dogwoodSigningKey").getOrElse(developmentSigningKey),
)
apply(from = rootProject.file("gradle/content-addressed-modules.gradle.kts"))
