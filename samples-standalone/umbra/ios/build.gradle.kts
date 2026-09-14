/*
 * Umbra on iOS -- the framework an existing Xcode project would link, built outside the engine.
 *
 * `engine/samples/ios-embed` proves that the engine can produce an embeddable framework. It cannot
 * prove that a *product* can, because it lives inside the engine's own build and resolves every
 * module by path. This is the same framework built the way a product builds one: `dogwood-host`
 * resolved from a repository as a Kotlin/Native artifact, the product's own design system compiled
 * for iOS beside it, and the three build-configuration lines each of which was learned from a link
 * failure -- copied verbatim from `ios-embed`, which is what a product is told to do.
 *
 * **What this module proves, and what it does not.** That the iOS variants of the published
 * artifacts exist, resolve, compile against a product's generated bindings, and LINK into a static
 * framework with the symbols an application needs. It does not run: there is no simulator in this
 * check and the verdict is a link. The render is covered on iOS by the engine's own drills against
 * `samples/slice-ios`.
 */
plugins {
  kotlin("multiplatform")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

kotlin {
  jvmToolchain(21)

  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "UmbraEmbed"
      // STATIC, and this is the choice a product most often gets wrong. Compose Multiplatform pulls
      // in Skiko, and a dynamic framework carrying it must be embedded AND signed by the consuming
      // target, with the dynamic-linking cost paid at every launch.
      isStatic = true
      // Zipline's cache is SQLDelight over SQLiter, whose cinterop bindings expect the platform to
      // supply `libsqlite3`. Stating it here means an Xcode project that links this framework and
      // nothing else still links, instead of failing on twenty-odd `_sqlite3_*` symbols.
      linkerOpts += "-lsqlite3"
      // Everything a Swift caller needs, exported by name. Without this the Kotlin API is compiled
      // in but not visible: the header would carry the factory and none of the types it takes.
      export("dev.dogwood:dogwood-host:0.1.0")
      export(project(":design"))
    }
  }

  sourceSets {
    iosMain {
      dependencies {
        // `api`, not `implementation`, because `export(...)` above requires it.
        api("dev.dogwood:dogwood-host:0.1.0")
        api(project(":design"))
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      }
    }
  }
}
