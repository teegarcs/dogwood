import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinAndroid)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

/** Stages the compiled guest into assets. Not Layer 3 delivery; see GuestBundle's note. */
val stageGuest by tasks.registering(Copy::class) {
  dependsOn(":samples:slice-guest:jsBrowserProductionWebpackZipline")
  from(project(":samples:slice-guest").layout.buildDirectory.dir("zipline/ProductionWebpack"))
  into(layout.buildDirectory.dir("generated/ziplineAssets/zipline"))
}

android {
  namespace = "dev.dogwood.slice.android"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.dogwood.slice.android"
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.compileSdk.get().toInt()
    versionCode = 1
    versionName = "0.1"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/ziplineAssets"))
}

tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
  dependsOn(stageGuest)
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

dependencies {
  implementation(project(":dogwood-host"))
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.ui)
  implementation(libs.androidx.activity.compose)
  implementation(libs.coroutines.android)
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}
