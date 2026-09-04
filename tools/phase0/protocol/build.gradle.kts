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
  js(IR) {
    browser()
  }
  // The iOS runner compiles the same service interfaces; Zipline publishes Kotlin/Native
  // artifacts for these targets at the pinned version, so nothing here is conditional.
  iosSimulatorArm64()
  iosArm64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain {
      dependencies {
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
  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}
