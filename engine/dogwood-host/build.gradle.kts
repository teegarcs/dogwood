plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
  // `zipline.take` is rewritten into an adapter call by Zipline's Kotlin compiler plugin. The
  // call site is here, in the host layer, so the plugin has to be here too -- applying it only
  // to the sample leaves this module compiling to a runtime error.
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

  applyDefaultHierarchyTemplate()

  sourceSets {
    // Both current host targets run on a Java Virtual Machine, and the threading assertions
    // need thread identity, which common Kotlin does not expose. When the iOS and Web hosts
    // arrive they supply their own actuals rather than inheriting this.
    val jvmAndroidMain by creating {
      dependsOn(commonMain.get())
      dependencies {
        // ZiplineLoader's Java-Virtual-Machine bindings take an OkHttp client and an Okio file
        // system; both are host-side concerns the guest never sees.
        api(libs.okhttp)
      }
    }

    jvmMain.get().dependsOn(jvmAndroidMain)
    androidMain.get().dependsOn(jvmAndroidMain)

    // Generated host bindings. Not committed; regenerated from the surface on every build.
    commonMain.get().kotlin.srcDir(rootProject.layout.buildDirectory.dir("generated/dogwood/host"))

    commonMain {
      dependencies {
        api(project(":dogwood-protocol"))
        api(libs.zipline)
        api(libs.zipline.loader)
        // One binding implementation reaches Android, desktop, Web and iOS, which is the whole
        // reason the host renders through Compose Multiplatform rather than native widgets.
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material3)
        api(compose.ui)
        implementation(libs.coroutines.core)
        implementation(libs.okio)
        api(libs.coil.compose)
        api(libs.coil.network)
      }
    }
    androidMain {
      dependencies {
        implementation(libs.coroutines.android)
      }
    }
    jvmTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }
  }
}

// SignatureTest asserts on the manifest the build actually produces, not on a fixture, so a
// change that silently stopped signing would fail rather than pass quietly.
tasks.named("jvmTest") {
  dependsOn(":samples:slice-guest:jsBrowserProductionWebpackZipline")
}

android {
  namespace = "dev.dogwood.host"
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
