/*
 * Project Dogwood -- the Compose Multiplatform iOS host (roadmap Phase 6).
 *
 * **There is deliberately no Xcode project here.** Compose Multiplatform on iOS renders through
 * Skiko onto a Metal layer inside a plain `UIViewController`
 * ([Layer 5 ADR-004](../../../adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md)),
 * and Kotlin/Native can produce the application executable itself, so an Xcode project would add
 * a second build system and a checked-in `project.pbxproj` for no capability. The `iosApp` task
 * below assembles the `.app` bundle the simulator installs: an executable, an `Info.plist`, and
 * nothing else.
 *
 * A product integrating this into an existing iOS application would do the opposite -- build a
 * framework and embed it -- which is the arrangement [ADR-004] describes and which changes
 * nothing above this file.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.zipline)
}

kotlin {
  jvmToolchain(21)

  // All three targets compile, so the host code is proven portable to a device and to an Intel
  // simulator. Only the Apple-silicon simulator gets an **executable**, and only a debug one: each
  // link produces a seventy-five-megabyte binary and takes minutes, and `./gradlew build` should
  // not spend a quarter of an hour producing binaries nothing installs. Shipping to a device is an
  // Xcode-project concern this sample deliberately does not take on.
  iosArm64()
  iosX64()
  iosSimulatorArm64().let { target ->
    target.binaries.executable(listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG)) {
      baseName = "DogwoodSlice"
      entryPoint = "dev.dogwood.slice.ios.main"
      // Compose Multiplatform on iOS is Skiko over Metal. Skiko's Objective-C symbols are pulled
      // in by the framework list, and UIKit is linked because the application is a UIKit
      // application whose root view controller happens to be drawn by Compose.
      freeCompilerArgs += listOf("-Xbinary=bundleId=dev.dogwood.slice.ios")
      // Zipline's cache is SQLDelight over SQLiter, whose cinterop bindings expect the platform
      // to supply `libsqlite3`. A framework consumer inherits that link flag from the umbrella
      // Xcode target; an executable produced directly by Kotlin/Native has no umbrella, so the
      // flag is stated here. Without it the link fails on twenty-odd `_sqlite3_*` symbols.
      linkerOpts += "-lsqlite3"
    }
  }

  sourceSets {
    iosMain {
      dependencies {
        implementation(project(":dogwood-host"))
        // Acme's design system, so a product's own components render here too -- the same
        // dependency the Android, desktop and web hosts already had. Its absence was invisible
        // until `tools/skew-drill/run-ios.sh` reported three unknown widget tags on a simulator.
        implementation(project(":samples:product-design-system"))
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(libs.coroutines.core)
      }
    }
  }
}

/**
 * The simulator device this build targets.
 *
 * Apple silicon simulators are `ios_simulator_arm64`; an Intel machine would want `iosX64`. Both
 * are declared above, so this is a choice of which one to bundle rather than a limitation.
 */
private val simulatorTarget = "IosSimulatorArm64"

/**
 * Assembles `build/DogwoodSlice.app`, ready for `xcrun simctl install`.
 *
 * A `.app` for the simulator is a directory with an executable and an `Info.plist` in it; there
 * is no code signing to do, because the simulator does not check one.
 */
val iosApp by tasks.registering(Sync::class) {
  val linkTask = tasks.named("linkDebugExecutable$simulatorTarget")
  dependsOn(linkTask)
  // The guest is fetched from the development server at run time, exactly as on Android and
  // desktop, so the bundle carries no payload -- only the trusted key, compiled into the host.
  dependsOn(":samples:slice-guest:jsBrowserProductionWebpackZipline")

  from(layout.projectDirectory.file("src/iosApp/Info.plist"))
  from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugExecutable/DogwoodSlice.kexe")) {
    // A `.app` names its executable in `CFBundleExecutable` and the file must match; Kotlin/Native
    // calls an executable `.kexe`, which is a Kotlin convention rather than an Apple one.
    rename { "DogwoodSlice" }
  }
  into(layout.buildDirectory.dir("DogwoodSlice.app"))
}
