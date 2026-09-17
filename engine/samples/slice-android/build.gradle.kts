import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinAndroid)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

// The guest is no longer bundled into the application. It arrives over the network, verified
// against a signing key, which is what Layer 3 is for.
android {
  namespace = "dev.dogwood.slice.android"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "dev.dogwood.slice.android"
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.compileSdk.get().toInt()
    versionCode = 1
    versionName = "0.1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    /*
     * Whether this build registers the generated Material 3 tier.
     *
     * A build flag rather than a runtime switch, because that is what it models: a host either
     * ships the tier's bytes or it does not, and a payload that declares `androidx.material3` must
     * be refused before `start` by one that does not (claim `B6`, ADR-061). A runtime toggle would
     * be testing a different thing -- a host that has the tier and pretends otherwise.
     *
     * `-PdogwoodMaterial3=false` builds the client without it; `tools/skew-drill/run-material-preflight.sh`
     * is what does that, and the default is the ordinary product build.
     */
    buildConfigField(
      "boolean",
      "DOGWOOD_MATERIAL3",
      (providers.gradleProperty("dogwoodMaterial3").getOrElse("true") != "false").toString(),
    )
  }

  buildFeatures { buildConfig = true }

  /*
   * The instrumented conformance drills run against the RELEASE build -- the minified one.
   *
   * This is the runtime half of audit finding A1. Building under R8 catches a missing class;
   * only running under it catches a stripped serializer or an unbound service, whose symptom is a
   * host that renders nothing. Pointing the drills at the release build means every Android cell
   * in the conformance matrix is graded against what a user would actually install, on every
   * local run, forever -- which is a stronger statement than any one-off smoke check.
   */
  testBuildType = "release"

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  /*
   * The release build shrinks, and that is the point of it existing.
   *
   * The adoption audit (`plans/adoption-audit.md` A1) found that no build in this repository had
   * ever run under R8 -- and this stack is exactly the shape a shrinker breaks: Zipline binds
   * services at the boundary by generated adapters, and kotlinx-serialization relies on generated
   * serializers that vanish when nothing keeps them. The plausible first symptom is not a crash;
   * it is a host that starts, verifies, and renders nothing.
   *
   * Signed with the debug key so it installs on an emulator without a keystore ceremony. That is
   * fine for what this build is for -- proving the engine survives R8 -- and would not be fine for
   * a shipping build, which is the owner's keystore.
   */
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // The instrumented-test APK is minified alongside this build type; its harness needs one
      // -dontwarn for compile-only annotations. Test-only -- nothing here ships.
      testProguardFiles("proguard-test.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

dependencies {
  implementation(project(":dogwood-host"))
  implementation(project(":samples:product-design-system"))
  implementation(project(":dogwood-material3"))
  implementation(project(":dogwood-foundation"))
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.ui)
  implementation(libs.androidx.activity.compose)
  implementation(libs.coroutines.android)

  androidTestImplementation(libs.androidx.test.runner)
  // See proguard-rules.pro: `androidx.tracing` is kept in the app instead, because the Android
  // Gradle Plugin excludes from the test APK anything the app's dependency graph provides -- so
  // adding it here is silently dropped, which was watched to happen rather than assumed.

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.uiautomator)
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}
