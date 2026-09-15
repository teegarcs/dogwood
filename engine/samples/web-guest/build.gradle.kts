/*
 * Project Dogwood -- the real guest, compiled for a Web Worker.
 *
 * The same screens as the mobile payload, from `samples/slice-screens`, driven by the same
 * `DogwoodGuest`. What this module adds is an entry point that carries a batch to `postMessage`
 * instead of to a Zipline service.
 *
 * No Zipline plugin here, and that is the point: nothing in this build knows what Zipline is.
 */
plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
}

kotlin {
  jvmToolchain(21)
  js(IR) {
    outputModuleName.set("web-guest")
    browser {
      commonWebpackConfig {
        outputFileName = "guest-kotlin.js"
        // A Worker script is loaded by `new Worker(url)`, not by a page, so webpack must not emit
        // anything that assumes a `document`. `web` would inject a DOM-based chunk loader that
        // throws the moment the Worker starts.
        output?.globalObject = "self"
      }
    }
    binaries.executable()
  }
  sourceSets {
    jsMain {
      dependencies {
        implementation(project(":dogwood-compose"))
        implementation(project(":samples:slice-screens"))
      }
    }
  }
}

/*
 * The authoring check Layer 1 requires.
 *
 * Invoked as a command rather than through the `io.github.teegarcs.dogwood.guest` plugin, for the reason the
 * generator is invoked as one here too: the plugin lives in this build, and applying it by
 * identifier inside the build that defines it would need a composite build or a prior publish. A
 * product applies the plugin and never sees this shape.
 */
val dogwoodGuestCheck by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Rejects guest code that would tick the boundary every frame"
  classpath = project(":dogwood-codegen").sourceSets["main"].runtimeClasspath
  mainClass.set("dev.dogwood.codegen.guest.GuestCheckKt")
  val sources = project.file("src")
  inputs.dir(sources).withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.upToDateWhen { false }
  argumentProviders.add { listOf(sources.absolutePath) }
}

tasks.named("check") { dependsOn(dogwoodGuestCheck) }
