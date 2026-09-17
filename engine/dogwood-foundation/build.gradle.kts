/*
 * Project Dogwood -- the foundation, layout and ui tiers: host bindings generated from the
 * libraries' own sources.
 *
 * **Three segments, one module, on purpose.** `androidx.foundation` (254),
 * `androidx.foundation.layout` (253) and `androidx.ui` (252) are three dictionary segments by
 * ADR-072 D-B, and they could have been three Gradle modules the way `dogwood-material3` is one.
 * They are not, for three reasons that all point the same way. They share a classpath -- the same
 * `compose.foundation` and `compose.ui` artifacts back all three, so splitting them would publish
 * three modules with one dependency set. They share a page-weight decision: registering a tier on
 * the web costs bytes (ADR-066's attribution rule, `G5` in `tools/conformance/budgets.tsv`), and
 * the question a host actually faces is "do I want the generated Compose primitives", not "do I
 * want `androidx.ui` but not `androidx.foundation.layout`". And a host that wants either of the
 * other two wants the layout one: `Box`, `Column` and `Row` are where a payload puts everything
 * else, so the layout segment is the floor of any answer that is not "none".
 *
 * They are also small. Fifteen bound composables between them -- eight in foundation-layout, four
 * in `ui`, three in `foundation` -- against Material 3's eighty-eight, because most of what these
 * libraries publish is a lazy layout, a draw scope or a text field, which the bindability rule
 * refuses and the coverage report explains one row at a time.
 *
 * **The primitive tier stays.** Segment 0 still offers `Column`, `Row` and `Box`, every payload in
 * the field uses those, and ADR-072 D-I says both stay callable with a payload picking by import.
 * Nothing here removes or changes them.
 *
 * All three tiers emit into ONE host package, `dev.dogwood.foundation`. A generated binding calls
 * the readers for Compose's closed sets by simple name, so a reader must live in the binding's own
 * package; three packages would mean three copies of `Readers.kt` inside one module. The binding
 * objects a host registers are told apart by their prefixes -- `FoundationBinding`,
 * `FoundationLayoutBinding`, `UiBinding` -- and each carries its own segment identifier and its own
 * version.
 *
 * Nothing in `src/commonMain` is generated except through the three tasks in
 * `dogwood-codegen/build.gradle.kts`; the one hand-written file is the readers those bindings call.
 */
plugins {
  `maven-publish`
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

group = "io.github.teegarcs"
version = "0.1.0"

// The three tiers, and the generated root each one writes. Separate roots because Gradle refuses a
// task output written underneath another task's declared output; see the codegen build file.
val tierTasks = mapOf(
  "generateFoundation" to "foundation",
  "generateFoundationLayout" to "foundation-layout",
  "generateUi" to "ui",
)

kotlin {
  jvmToolchain(21)

  /*
   * The three libraries' real opt-in markers, passed to the compiler rather than written into the
   * generated files -- because the generated files name their markers by simple name and these
   * three are declared in a different package from the components that carry them. See
   * `src/commonMain/kotlin/dev/dogwood/foundation/OptInMarkers.kt`, which is the other half of this
   * and says what the real fix is. Delete both together.
   */
  compilerOptions {
    optIn.addAll(
      "androidx.compose.foundation.ExperimentalFoundationApi",
      "androidx.compose.foundation.gestures.ExperimentalTapGestureDetectorBehaviorApi",
      "androidx.compose.ui.input.pointer.util.ExperimentalVelocityTrackerApi",
    )
  }

  jvm()
  androidTarget {
    publishLibraryVariants("release")
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { browser() }

  applyDefaultHierarchyTemplate()

  sourceSets {
    // The generated bindings, each wired through its own producing task so every consumer of this
    // source set -- including a sources jar, which packages the directory rather than compiling it
    // -- inherits the dependency. A bare `srcDir` plus a `dependsOn` on the compilations is what
    // broke `publishToMavenLocal` when the Material 3 tier landed; that module's build file and
    // `dogwood-compose`'s both record it.
    for ((task, module) in tierTasks) {
      commonMain.get().kotlin.srcDir(
        project(":dogwood-codegen").tasks.named(task).map {
          rootProject.layout.buildDirectory.dir("generated/dogwood-foundation/$module/host").get()
        },
      )
    }

    commonMain {
      dependencies {
        api(project(":dogwood-host"))
        api(compose.runtime)
        api(compose.foundation)
        api(compose.ui)
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    // Render tests on every target that can host a composition out of process, exactly as
    // dogwood-material3 and dogwood-host arrange them and for the same reason: Android's unit-test
    // target has no real Android in it, and the accessibility drill is where Android renders.
    val renderTest by creating {
      dependsOn(commonTest.get())
      dependencies {
        implementation(kotlin("test"))
        @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
      }
    }
    jvmTest.get().dependsOn(renderTest)
    iosTest.get().dependsOn(renderTest)
    getByName("wasmJsTest").dependsOn(renderTest)

    jvmTest {
      dependencies {
        implementation(compose.desktop.currentOs)
      }
    }
  }
}

android {
  namespace = "dev.dogwood.foundation"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  for (task in tierTasks.keys) dependsOn(":dogwood-codegen:$task")
}
