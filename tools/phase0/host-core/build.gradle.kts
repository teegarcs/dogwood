/*
 * Project Dogwood -- the Phase 0 driver, once, for every host that runs it.
 *
 * This was a directory of sources that three host modules each compiled with `srcDir`, which was
 * fine while all three were Java Virtual Machine dialects. Phase 6 step 3 asks for the same
 * experiments on iOS, and Kotlin/Native is not a dialect of anything -- so the shared driver had to
 * become a real multiplatform module with the two or three genuinely platform-specific calls named
 * as such, rather than a folder that happened to compile everywhere.
 *
 * What is platform-specific turned out to be very little: a monotonic clock, reading a file, and
 * gzip. Everything the experiments actually measure -- Zipline, QuickJS, the protocol, the
 * encodings -- is common, which is the reason the numbers are comparable at all.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
  // The real Gradle plugin, not just its compiler-plugin artifact on the classpath.
  //
  // `Zipline.take` is rewritten by a compiler plugin into an adapter call; without the rewrite it
  // reaches a stub that throws "is the Zipline plugin configured?". Adding the artifact to
  // `kotlinCompilerPluginClasspath` by hand -- which is what a Java Virtual Machine-only module
  // does -- reaches only that one configuration, and a multiplatform module has one per target.
  // So the Java Virtual Machine compiled correctly and Kotlin/Native silently did not, and the
  // failure appeared at run time on the simulator rather than at build time anywhere.
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
  iosSimulatorArm64()
  iosArm64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain {
      dependencies {
        api(project(":protocol"))
        api(libs.zipline)
        api(libs.zipline.loader)
        api(libs.coroutines.core)
        api(libs.serialization.json)
        api(libs.okio)
      }
    }
    // The Android target compiles the same sources as the Java Virtual Machine one; the default
    // hierarchy gives them no shared parent, so the actuals are pointed at explicitly rather than
    // duplicated.
    val androidMain by getting { kotlin.srcDir("src/jvmMain/kotlin") }
  }
}

android {
  namespace = "dev.dogwood.host.core"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}
