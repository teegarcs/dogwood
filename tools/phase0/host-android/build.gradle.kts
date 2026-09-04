import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinAndroid)
  alias(libs.plugins.kotlinSerialization)
}

/** Stages the compiled guest into the application's assets so the device can load it. */
val stageGuest by tasks.registering(Copy::class) {
  dependsOn(":guest:jsBrowserProductionWebpackZipline")
  from(project(":guest").layout.buildDirectory.dir("zipline/ProductionWebpack"))
  into(layout.buildDirectory.dir("generated/ziplineAssets/zipline"))
}

android {
  namespace = "dev.dogwood.host.android"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.dogwood.host.android"
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.compileSdk.get().toInt()
    versionCode = 1
    versionName = "0.1"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  // The Copy task's destination is `.../ziplineAssets/zipline`, so the assets root is its
  // parent: the guest lands in the application under `assets/zipline/`.
  sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/ziplineAssets"))

  packaging {
    // Zipline ships the QuickJS native library; keep it uncompressed so it maps directly.
    jniLibs.useLegacyPackaging = false
  }
}

kotlin {
  jvmToolchain(21)
  // Compile with the JDK 21 toolchain but emit Java 11 bytecode, which is what the Android
  // Gradle Plugin's compileOptions above declare and what D8 expects.
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
  dependsOn(stageGuest)
}

dependencies {
  implementation(project(":host-core"))
  implementation(project(":protocol"))
  implementation(libs.zipline)
  implementation(libs.zipline.loader)
  implementation(libs.coroutines.core)
  implementation(libs.serialization.json)
  implementation(libs.okio)
  implementation(libs.androidx.activity)

  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}
