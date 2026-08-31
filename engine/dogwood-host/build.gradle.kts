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
    val jvmAndroidMain by creating { dependsOn(commonMain.get()) }
    jvmMain.get().dependsOn(jvmAndroidMain)
    androidMain.get().dependsOn(jvmAndroidMain)

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
      }
    }
    androidMain {
      dependencies {
        implementation(libs.coroutines.android)
      }
    }
  }
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
