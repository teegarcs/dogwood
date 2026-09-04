/*
 * Project Dogwood -- the Phase 0 harness on iOS (roadmap Phase 6 step 3).
 *
 * A console executable, not an application: this measures an interpreter and a protocol, and a
 * user interface would only add a renderer's cost to numbers that are supposed to exclude it. The
 * `.kexe` runs under `xcrun simctl spawn`, which puts it in the simulator's runtime while leaving
 * it able to read the compiled guest from the ordinary file system.
 */
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  jvmToolchain(21)

  iosSimulatorArm64 {
    binaries.executable {
      entryPoint = "dev.dogwood.host.main"
      baseName = "phase0"
    }
  }

  sourceSets {
    // `src/iosMain` is already the hierarchy's own name for this, so it needs no `srcDir` --
    // adding one lists the file under two fragments and the compiler refuses it.
    val iosSimulatorArm64Main by getting {
      dependencies {
        implementation(project(":host-core"))
      }
    }
  }
}

dependencies {
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}
