import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

/*
 * Project Dogwood -- the artifact an existing iOS application embeds.
 *
 * `slice-ios` *is* an application: Kotlin/Native owns `UIApplicationMain`, there is no Xcode
 * project, and a product with an existing app cannot use any of it. ADR-004 said such a product
 * "builds a framework and embeds it", and nothing built one -- which is the adoption audit's B4
 * and the concrete half of the framework grade's adoption blocker.
 *
 * This module is that framework. It is deliberately a **library, not an application**: a product
 * replaces its one file with their own registration and screens, keeps this build configuration
 * verbatim, and gets an XCFramework their Xcode target links.
 *
 * What earns its keep here is the configuration rather than the code -- the four lines below were
 * each learned by a link failure somewhere in this repository, and a product that writes them from
 * scratch learns them the same way.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin {
  jvmToolchain(21)

  listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
    target.binaries.framework {
      baseName = "DogwoodEmbed"
      // STATIC, and this is the choice a product most often gets wrong.
      //
      // Compose Multiplatform pulls in Skiko, and a dynamic framework carrying it must be embedded
      // AND signed by the consuming target, with the dynamic-linking cost paid at every launch. A
      // static framework is linked into the host application's binary once, which is what an
      // existing application wants: no embed phase, no second signature, no launch penalty.
      isStatic = true
      // Zipline's cache is SQLDelight over SQLiter, whose cinterop bindings expect the platform to
      // supply `libsqlite3`. A framework consumer normally inherits this from its own Xcode target;
      // stating it here means an Xcode project that links `DogwoodEmbed.xcframework` and nothing
      // else still links, instead of failing on twenty-odd `_sqlite3_*` symbols.
      linkerOpts += "-lsqlite3"
      // Everything a Swift caller needs, exported by name. Without this the Kotlin API is compiled
      // in but not visible: the header would carry the factory and none of the types it takes.
      export(project(":dogwood-host"))
      export(project(":samples:product-design-system"))
      export(project(":dogwood-material3"))
    }
  }

  sourceSets {
    iosMain {
      dependencies {
        // `api`, not `implementation`, because `export(...)` above requires it: an exported
        // dependency must be on the consumer's compile classpath, and Kotlin/Native refuses the
        // combination outright rather than producing a header with holes in it.
        api(project(":dogwood-host"))
        api(project(":samples:product-design-system"))
        api(project(":dogwood-material3"))
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(libs.coroutines.core)
      }
    }
  }
}

/*
 * The XCFramework -- one artifact carrying every architecture an Xcode project might build for.
 *
 * A product ships to devices (`iosArm64`) and its engineers run simulators on both Apple silicon
 * and Intel; three separate frameworks would make the consuming project choose, and choosing wrong
 * fails at link time on a machine that is not yours. `XCFrameworkTask` bundles all three.
 */
val xcframework = XCFramework("DogwoodEmbed")

kotlin.targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java) {
  binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.Framework::class.java) {
    xcframework.add(this)
  }
}
