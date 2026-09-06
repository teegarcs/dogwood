/*
 * Project Dogwood -- the wire, and nothing that carries it.
 *
 * The change grammar, the identifier spaces, the host environment and the tag types: everything both
 * sides of the boundary must agree on, with **no transport dependency at all**.
 *
 * It was split out of `dogwood-protocol` because that module has `api(libs.zipline)` in its common
 * source set, and Zipline publishes for the Java Virtual Machine, Android and JavaScript -- not for
 * WebAssembly. The web profile ([Layer 5 ADR-032](../../adrs/layer-5/ADR-032-the-web-profile.md))
 * has no Zipline at all: the guest is ordinary JavaScript in a Worker and the bridge is
 * `postMessage`. So a protocol module that could not compile without Zipline made the protocol
 * unreachable on the one platform whose whole point is that it does not need one.
 *
 * The package is deliberately unchanged (`dev.dogwood.protocol`), so nothing that imports these
 * types had to move.
 */
plugins {
  // Published, so a product outside this repository can depend on it. Coordinates and a
  // version, and nothing else: where the artifacts actually go is a deployment decision.
  `maven-publish`
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
}

group = "dev.dogwood"
version = "0.1.0"


kotlin {
  jvmToolchain(21)

  jvm()
  androidTarget {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  // Pinned for the reason `dogwood-compose` records at length: the Kotlin/JavaScript module
  // name defaults to something derived from `group`, `internal` declarations are mangled
  // against it, and adding a publishing group put a dot in it. This module has not been
  // bitten; the hazard is identical and the fix is one line.
  js(IR) {
    outputModuleName.set("dogwood-wire")
    browser()
  }
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { browser() }
  iosArm64()
  iosSimulatorArm64()
  iosX64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    // The generated segment-version vector. Not committed, like every other generated source.
    // Wired through the producing task, so every consumer of this source set inherits the
    // dependency -- including the sources jar publishing asks for. See `dogwood-host`.
    commonMain.get().kotlin.srcDir(
      project(":dogwood-codegen").tasks.named("generateDesignSystem").map {
        rootProject.layout.buildDirectory.dir("generated/dogwood/wire").get()
      },
    )

    commonMain {
      dependencies {
        api(libs.serialization.json)
        api(libs.coroutines.core)
      }
    }
  }
}

android {
  namespace = "dev.dogwood.wire"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":dogwood-codegen:generateDesignSystem")
}

