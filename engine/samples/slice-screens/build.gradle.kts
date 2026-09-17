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
    // Tests run on Node, as dogwood-compose's do and for the same reason: what
    // `MaterialScreenCoverageTest` asserts is a property of a real composition -- which widget tags
    // this screen actually sends -- so it has to run where the real Compose runtime runs.
    nodejs()
  }

  /*
   * The preview compilation (plans/close-the-backlog.md Group 4; Layer 1 section 3).
   *
   * **The same source, compiled twice.** `MaterialScreen.kt` and `AboutScreen.kt` below are
   * compiled for Kotlin/JavaScript against `dogwood-compose`, where every call records a wire
   * operation, and for the Java Virtual Machine against `dogwood-compose-preview`, where the same
   * call reaches real Compose. Neither compilation knows about the other and neither file has a
   * line of `expect`/`actual` in it: the two back ends publish the same package and the same
   * names, which is the whole of the trick.
   *
   * Only those two files are on the preview path today, by an explicit `include` rather than by
   * a shared directory, and the reason is honest rather than architectural: the preview back end
   * covers the vocabulary those two screens use, which is most of the primitive tier, the design
   * system and the Material 3 catalogue. `ExploreScreen` and `FeedScreen` reach further -- host
   * networking, lazy windowing, the pager -- and adding them is adding delegates, not adding a
   * mechanism.
   */
  jvm()

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
    jsTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    jvmMain {
      // Two roots, and the filter below is what keeps them from being everything. `src/jsMain` is
      // the payload's own screens; `preview/main` is the launcher and Acme's preview delegates,
      // which are deliberately NOT under `src` -- `dogwoodGuestCheck` reads `src` and is a check
      // on *guest* code, and a desktop window is not guest code.
      kotlin.setSrcDirs(listOf("src/jsMain/kotlin", "preview/main/kotlin"))
      kotlin.include(
        "dev/dogwood/slice/MaterialScreen.kt",
        "dev/dogwood/slice/AboutScreen.kt",
        "dev/dogwood/slice/preview/**",
        "dev/acme/**",
      )
      dependencies {
        // The other back end. Same packages, same names, real Compose underneath.
        api(project(":dogwood-compose-preview"))
        // Acme's real implementations, which the preview delegates call directly -- the
        // arrangement Layer 1 Milestone 3 describes for a registered design system.
        implementation(project(":samples:product-design-system"))
      }
    }
  }
}

/*
 * The window.
 *
 *     ./gradlew :samples:slice-screens:preview -Pscreen=material
 *     ./gradlew :samples:slice-screens:preview -Pscreen=about -Pdark
 *
 * and the gate that can run without one:
 *
 *     ./gradlew :samples:slice-screens:preview -Pscreen=material -Pheadless
 *
 * which composes the screen into an off-screen Skia surface, renders a frame, and exits non-zero
 * if either throws. That is the only form of this task a machine can grade, so it is the form the
 * checks run; see `PreviewMain.kt` for what a preview can and cannot tell you.
 */
val preview by tasks.registering(JavaExec::class) {
  group = "application"
  description = "Opens a payload screen in a Compose Desktop window, rendered by real Compose."
  val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
  classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
  dependsOn(jvmMain.compileTaskProvider)
  mainClass.set("dev.dogwood.slice.preview.PreviewMainKt")
  // Compose composes deeply and the desktop sample already runs with a larger stack for it.
  jvmArgs("-Xss8m")
  argumentProviders.add {
    buildList {
      add("--screen=" + (project.findProperty("screen") as String? ?: "material"))
      if (project.hasProperty("dark")) add("--dark=true")
      if (project.hasProperty("headless")) add("--headless")
      (project.findProperty("out") as String?)?.let { add("--out=$it") }
    }
  }
}

/*
 * Where the guest's own change batches are written for the Java Virtual Machine side to replay.
 *
 * `MaterialScreenCoverageTest` composes every section of the Material catalogue to count what it
 * uses; writing what it sent costs nothing extra and gives `:samples:slice-desktop:test` a real
 * payload's wire to render through the real host bindings, with no device and no Zipline.
 */
val materialWireDirectory: Provider<Directory> = layout.buildDirectory.dir("material-wire")

tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("jsNodeTest") {
  environment("DOGWOOD_WIRE_OUT", materialWireDirectory.get().asFile.absolutePath)
  outputs.dir(materialWireDirectory)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":samples:product-design-system:generateAcme")
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
