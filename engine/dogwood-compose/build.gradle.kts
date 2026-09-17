plugins {
  // Published, so a product outside this repository can depend on it. Coordinates and a
  // version, and nothing else: where the artifacts actually go is a deployment decision.
  `maven-publish`
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.zipline)
}

group = "io.github.teegarcs"
version = "0.1.0"


kotlin {
  jvmToolchain(21)
  js(IR) {
    /*
     * Pinned, so publishing coordinates cannot change it.
     *
     * The Kotlin/JavaScript module name defaults to something derived from the project's `group`,
     * and `internal` declarations are name-mangled against it. Adding `group = "dev.dogwood"` for
     * publishing therefore renamed the module to one containing a **dot**, and every browser test
     * in this module died at runtime with `then_babg2s_k$ is not a function` -- `Modifier.then`,
     * whose second overload is internal, resolving against a module the loader could not name.
     *
     * A clean build did not fix it, which is what said it was a naming problem rather than a stale
     * one. Pinning the name makes the emitted module independent of where the artifact is published,
     * which is the relationship that should have held all along.
     *
     * **It covers less than it looks like it covers, and 2026-09-14 is where that was measured.**
     * `outputModuleName` pins the name of the emitted JavaScript. It does *not* pin the klib's
     * `unique_name`, which the manifest records as `<group>:<project>` and which is what `internal`
     * declarations are actually mangled against. Moving the group to `io.github.teegarcs` (ADR-071)
     * therefore re-mangled every internal in this module -- correctly and consistently, so a clean
     * build passes. What did not pass was a build with Gradle's **build cache** warm: the
     * Kotlin/JavaScript compile task's cache key does not capture `unique_name`, so cached output
     * mangled under the old group was restored beside freshly compiled callers and twenty-two
     * tests died on `then_vuiwuj_k$ is not a function` -- the same symptom as the defect above,
     * from an unrelated cause. `--no-build-cache` once, after any group change, is the whole fix.
     * Setting `compilerOptions.moduleName` was tried and does not move `unique_name`.
     */
    outputModuleName.set("dogwood-compose")
    browser()
    // Tests run on Node. The gate conditions this module has to satisfy -- wrapper scoping and
    // node identity across a reorder -- are properties of composition and of the applier, so
    // they must run where the real Compose runtime runs, which is Kotlin/JavaScript.
    nodejs()
  }

  sourceSets {
    // Generated guest stubs. Not committed; regenerated from the surface on every build.
    //
    // Wired through the producing task rather than as a bare directory, for the reason
    // dogwood-host's build file gives: a sources jar *packages* this directory rather than compiling
    // it, so a `dependsOn` on the compilations does not reach it, and Gradle refuses a jar that reads
    // another task's output without saying so. It refused on 2026-09-15, the first publish after the
    // Material 3 tier joined this source set.
    jsMain.get().kotlin.srcDir(
      project(":dogwood-codegen").tasks.named("generateDesignSystem").map {
        rootProject.layout.buildDirectory.dir("generated/dogwood/guest").get()
      },
    )
    // The Material 3 tier's guest stubs (plans/generator-v2.md, M2), from the tier's own generated
    // root and wired through its task -- so the sources jar, which packages the directory rather
    // than compiling it, depends on the generator too. A bare `dependsOn` on the compilations was
    // not enough for the design-system stubs either; see dogwood-host's build file.
    jsMain.get().kotlin.srcDir(
      project(":dogwood-codegen").tasks.named("generateMaterial3").map {
        rootProject.layout.buildDirectory.dir("generated/dogwood-material3/guest").get()
      },
    )

    /*
     * The foundation, layout and ui tiers' guest stubs, from the three generated roots the
     * `dogwood-foundation` module's tasks write (plans/close-the-backlog.md 2.3).
     *
     * Three source directories rather than one, because each tier is a separate Gradle task with a
     * separate output root -- a task output written underneath another task's declared output is,
     * to Gradle, an undeclared dependency, and the sources jar is what refuses it. Each one is
     * wired through its own producing task for the reason the Material 3 block above gives.
     *
     * The packages are `dev.dogwood.compose.foundation`, `.foundation.layout` and `.ui`, which is
     * what makes ADR-072's D-I true at the source level: a payload gets segment 0's `Column` or the
     * generated one by which package it imports, and both stay callable.
     */
    for ((task, module) in listOf(
      "generateFoundation" to "foundation",
      "generateFoundationLayout" to "foundation-layout",
      "generateUi" to "ui",
    )) {
      jsMain.get().kotlin.srcDir(
        project(":dogwood-codegen").tasks.named(task).map {
          rootProject.layout.buildDirectory.dir("generated/dogwood-foundation/$module/guest").get()
        },
      )
    }

    jsTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    jsMain {
      dependencies {
        api(project(":dogwood-protocol"))
        // The genuine Compose runtime, compiled to JavaScript. This is what makes `remember`,
        // `mutableStateOf`, `derivedStateOf` and recomposition behave as a developer expects,
        // with no Dogwood-specific protocol for any of them.
        api(libs.compose.runtime.js)
        api(libs.compose.runtime.saveable.js)
        implementation(libs.coroutines.core)
      }
    }
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":dogwood-codegen:generateDesignSystem")
  dependsOn(":dogwood-codegen:generateMaterial3")
  dependsOn(":dogwood-codegen:generateFoundation")
  dependsOn(":dogwood-codegen:generateFoundationLayout")
  dependsOn(":dogwood-codegen:generateUi")
}

// Zipline's API validator reads the same generated sources but is not a Kotlin compilation task,
// so the rule above does not reach it. Without this, `gradle build` fails with a missing implicit
// dependency the moment the generated directory is stale -- which is every clean checkout.
tasks.matching { it.name.contains("ZiplineApi") }.configureEach {
  dependsOn(":dogwood-codegen:generateDesignSystem")
  dependsOn(":dogwood-codegen:generateMaterial3")
  dependsOn(":dogwood-codegen:generateFoundation")
  dependsOn(":dogwood-codegen:generateFoundationLayout")
  dependsOn(":dogwood-codegen:generateUi")
}

