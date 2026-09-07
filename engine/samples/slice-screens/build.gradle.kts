/*
 * Project Dogwood -- the sample's screens, and the module that makes "one screen, three platforms"
 * a fact rather than a slogan.
 *
 * These were inside `slice-guest`, which is the **mobile** guest: a Kotlin/JavaScript executable
 * loaded into QuickJS by Zipline. That arrangement made the architecture's central claim
 * unprovable on the web, because the only other guest that existed was `web-slice`'s hundred lines
 * of hand-written JavaScript -- deliberately hand-written, to show the protocol is an interface
 * rather than an artefact of having Kotlin on both ends, and therefore not a demonstration that
 * real guest code runs there.
 *
 * So the screens live here, and both entry points depend on them: `slice-guest` binds them to a
 * Zipline service for mobile, and `web-guest` drives them from a Web Worker. **The screens do not
 * know which.** Nothing in this module names Zipline, `postMessage`, or a platform.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  jvmToolchain(21)
  js(IR) {
    outputModuleName.set("slice-screens")
    browser()
  }
  sourceSets {
    jsMain {
      dependencies {
        api(project(":dogwood-compose"))
      }
      // Acme's generated guest stubs, for the same reason `slice-guest` had them: the About screen
      // renders a product's own components, and that section is a screen like any other.
      kotlin.srcDir(
        project(":samples:product-design-system").layout.buildDirectory.dir("generated/acme/guest"),
      )
    }
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":samples:product-design-system:generateAcme")
}
