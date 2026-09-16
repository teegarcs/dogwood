/*
 * Umbra's design system, built entirely against published Dogwood artifacts.
 *
 * The whole file is the point. There is no `project(":dogwood-codegen")` here and no path to one:
 * the plugin is applied by identifier, the generator arrives as a dependency, and the runtime comes
 * from a repository. If this builds, a product outside this repository can use Dogwood.
 *
 * **Multiplatform since 2026-09-13**, because a real adopter's design system is. Until then this was
 * `kotlin("jvm")`, so the generated bindings -- and the `@Implementation` target they call -- had
 * only ever been compiled for the desktop host. A product's design system has to compile wherever
 * its host does: the Android and iOS applications and the web page all call the same generated
 * binding, and a binding that compiles on one of them and not the others is a binding that renders
 * a product's components as placeholders on the platforms nobody built for. The targets below are
 * every platform Dogwood's host has, and `tools/standalone-check/run.sh` compiles this module for
 * each of them.
 */
plugins {
  kotlin("multiplatform")
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.compose")
  // Compose Multiplatform's own plugin, which is what maps `compose.material3` onto the right
  // artifact for the platform being built. A product using Compose applies it anyway.
  id("org.jetbrains.compose")
  id("io.github.teegarcs.dogwood.codegen")
}

kotlin {
  jvmToolchain(21)

  jvm()
  androidTarget {
    // A library variant, so `:android` resolves this module's Android artifact rather than
    // falling through to the desktop one -- the same omission `dogwood-host` carried until
    // `tools/framework-grade/results/2026-09-09-run3.md` found it.
    publishLibraryVariants("release")
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  iosArm64()
  iosSimulatorArm64()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { browser() }

  sourceSets {
    commonMain {
      dependencies {
        implementation("io.github.teegarcs:dogwood-host:0.1.0")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
      }
    }
  }
}

android {
  namespace = "dev.umbra.design"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
}

// The generator is a *tool*: it runs before compilation, and nothing Umbra writes links against it.
val dogwoodGenerator by configurations.creating

dependencies {
  dogwoodGenerator("io.github.teegarcs:dogwood-codegen:0.1.0")
}

/*
 * Two decisions, and the plugin derives the rest.
 *
 * Compare this with what the same thing looked like before the plugin: fourteen command-line
 * arguments, three of them package names that had to be told apart, one output path per artifact,
 * and a `--wire-out` that had to be *omitted* or the engine's version vector was silently
 * overwritten with this segment's.
 */
dogwood {
  segment("umbraDesignSystem") {
    wireName.set("umbra.designsystem")
    // 0 and 1 are Dogwood's, and so are 201..255 (the generated Compose tiers, allocated downward
    // from 255 -- ADR-072); products allocate upward from 2. Acme is 2 in the engine's sample; Umbra
    // takes 3.
    segmentId.set(3)
    // 3 since `UmbraBadge` and `UmbraTone` (2 since `UmbraChip`): the dictionary is append-only
    // and a new component -- or a new enumeration -- is a new version, which is what lets a client
    // declare honestly what it can render (ADR-061's comparison).
    version.set(3)
    guestPackage.set("dev.umbra.guest")
    hostPackage.set("dev.umbra.design")
    // The component reference, generated from the same parse as the bindings. Committed rather
    // than left in `build/`, for the reason the lock is: a file nobody can open is not a record.
    referenceFile.set("../REFERENCE.md")
  }
}
