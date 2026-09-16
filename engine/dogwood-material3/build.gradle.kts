/*
 * Project Dogwood -- the Material 3 tier: host bindings generated from the library's own sources.
 *
 * Its own module rather than more files in `dogwood-host`, for one reason that is about bytes and
 * one that is about honesty. A binding that references every public Material 3 composable defeats
 * dead-code elimination for the whole library, and on the web that is a page-weight decision
 * (plans/generator-v2.md, D-H) which a host can only take if the tier is something it can leave
 * out -- so every host registers `Material3Binding` explicitly, and the web measurement can build
 * without it. And keeping the generated tier apart from the hand-written primitive tier keeps the
 * coverage report honest: what is in here is exactly what the generator produced, no more.
 *
 * Nothing in `src/commonMain` is generated except through the task below; the two hand-written
 * files are the readers the generated bindings call for names Compose keeps as closed sets.
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

kotlin {
  jvmToolchain(21)

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
    // The generated bindings, wired through the producing task so every consumer of this source
    // set -- including a sources jar -- inherits the dependency (the lesson dogwood-host records).
    commonMain.get().kotlin.srcDir(
      project(":dogwood-codegen").tasks.named("generateMaterial3").map {
        rootProject.layout.buildDirectory.dir("generated/dogwood-material3/host").get()
      },
    )

    commonMain {
      dependencies {
        api(project(":dogwood-host"))
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material3)
        api(compose.ui)
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    // Render tests on every target that can host a composition out of process, exactly as
    // dogwood-host arranges them and for the same reason: Android's unit-test target has no real
    // Android in it, and the accessibility drill is where Android renders.
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
  namespace = "dev.dogwood.material3"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":dogwood-codegen:generateMaterial3")
}
