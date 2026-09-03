plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.zipline)
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
