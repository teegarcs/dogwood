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
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  jvmToolchain(21)

  jvm()
  androidTarget {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  js(IR) { browser() }
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { browser() }
  iosArm64()
  iosSimulatorArm64()
  iosX64()

  applyDefaultHierarchyTemplate()

  sourceSets {
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
