plugins {
  // Published, so a product outside this repository can depend on it. Coordinates and a
  // version, and nothing else: where the artifacts actually go is a deployment decision.
  `maven-publish`
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.zipline)
}

group = "dev.dogwood"
version = "0.1.0"


kotlin {
  jvmToolchain(21)

  jvm()
  androidTarget {
    // Published, not merely compiled. See `dogwood-host/build.gradle.kts` for what the missing
    // line cost: an Android consumer silently resolving the Java 21 JVM artifact instead.
    publishLibraryVariants("release")
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  // Pinned for the reason `dogwood-compose` records at length: the Kotlin/JavaScript module
  // name defaults to something derived from `group`, `internal` declarations are mangled
  // against it, and adding a publishing group put a dot in it. This module has not been
  // bitten; the hazard is identical and the fix is one line.
  js(IR) {
    outputModuleName.set("dogwood-protocol")
    browser()
  }
  // Zipline publishes Kotlin/Native artifacts for all three iOS targets at the pinned version
  // (`app.cash.zipline:zipline-iosarm64`, `-iossimulatorarm64`, `-iosx64`, and the same three for
  // `zipline-loader`), so the service boundary compiles unchanged for the iOS host.
  iosArm64()
  iosSimulatorArm64()
  iosX64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain {
      dependencies {
        api(project(":dogwood-wire"))
        api(libs.zipline)
        api(libs.serialization.json)
        api(libs.coroutines.core)
      }
    }
  }
}

android {
  namespace = "dev.dogwood.protocol"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

