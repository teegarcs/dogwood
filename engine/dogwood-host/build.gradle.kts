plugins {
  // Published, so a product outside this repository can depend on it. Coordinates and a
  // version, and nothing else: where the artifacts actually go is a deployment decision.
  `maven-publish`
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
  // `zipline.take` is rewritten into an adapter call by Zipline's Kotlin compiler plugin. The
  // call site is here, in the host layer, so the plugin has to be here too -- applying it only
  // to the sample leaves this module compiling to a runtime error.
  alias(libs.plugins.zipline)
}

group = "dev.dogwood"
version = "0.1.0"


kotlin {
  jvmToolchain(21)

  jvm()
  androidTarget {
    /*
     * Published, not merely compiled -- and it was the second the day before this line existed.
     *
     * `androidTarget` on its own builds an Android variant inside this repository and ships
     * nothing: `publishToMavenLocal` produced `common`, `jvm`, `native` and `wasm`, with no
     * `androidJvm` anywhere in the module metadata. An Android consumer would resolve
     * `dogwood-host-jvm` through Kotlin Multiplatform's platform-compatibility rule, whose class
     * files are Java 21 bytecode against the `JVM_11` two lines below -- and it would do so
     * silently, because the fallback is legal.
     *
     * Nobody noticed because nobody had tried. The only consumer outside this repository is Umbra,
     * and Umbra is a desktop application, so the flagship platform's artifact path was the one path
     * the standalone proof did not cover. An independent grading run read the module metadata and
     * found it (`tools/framework-grade/results/2026-09-09-run3.md`).
     */
    publishLibraryVariants("release")
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  // Phase 6. Zipline, `zipline-loader`, Okio, Coil 3, `redwood-leak-detector` and Compose
  // Multiplatform all publish Kotlin/Native iOS artifacts at the pinned versions; the one
  // dependency that does not is `coil-network-okhttp`, which is why it moved down into
  // `jvmAndroidMain` below.
  iosArm64()
  iosSimulatorArm64()
  iosX64()
  // Layer 5 ADR-041. The browser is not a Zipline target and never will be -- the web guest is
  // JavaScript in a Worker -- but everything this module does *after* a batch arrives is portable,
  // and the web host had been reimplementing it. Adding the target is what makes `commonMain`
  // transport-free by compilation rather than by intention.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs { browser() }

  applyDefaultHierarchyTemplate()

  sourceSets {
    // The two Java-Virtual-Machine host targets share an implementation of everything that needs
    // a Java Virtual Machine: thread identity, `java.text` formatting, and OkHttp. iOS supplies
    // its own actuals in `iosMain` rather than inheriting these.
    /*
     * The Zipline layer (Layer 5 ADR-041).
     *
     * Delivery, the experience, the Zipline-backed service host, the session and the shell -- and
     * the leak detector, whose Redwood dependency publishes no WebAssembly artifact. Everything
     * that names a Zipline type lives here and nowhere else.
     *
     * The seam is enforced by compilation, not by review: `commonMain` has no Zipline dependency,
     * so anything reaching for one fails the `wasmJs` build immediately.
     */
    val ziplineMain by creating {
      dependsOn(commonMain.get())
      dependencies {
        api(project(":dogwood-protocol"))
        api(libs.zipline)
        api(libs.zipline.loader)
        // `implementation`, not `api`: Redwood marks its LeakDetector "for Redwood internal use
        // only", and this module's own `DogwoodLeakWatcher` is what callers see. It also
        // publishes no WebAssembly artifact, which is the second reason it is down here.
        implementation(libs.redwood.leak.detector)
      }
    }

    val jvmAndroidMain by creating {
      dependsOn(ziplineMain)
      dependencies {
        // ZiplineLoader's Java-Virtual-Machine bindings take an OkHttp client and an Okio file
        // system; both are host-side concerns the guest never sees.
        api(libs.okhttp)
        // Coil's OkHttp network fetcher. Java Virtual Machine and Android only -- there is no
        // `coil-network-okhttp-iosarm64` artifact at 3.4.0, because OkHttp is a Java library.
        // It registers itself through Coil's service loader, so no Dogwood code imports it and
        // moving it changes nothing about what `AsyncImage` does on Android.
        api(libs.coil.network)
      }
    }

    // The in-memory file system standing in for browser storage until ADR-041 step 2 finishes.
    // A test dependency in Okio's naming, a production one here, and the comment on
    // `platformFileSystem` says why that is temporary.
    val wasmJsMain by getting {
      dependencies {
        implementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
      }
    }

    // Runs in headless Chrome, which is the only place `localStorage` exists -- the durable
    // saved-state file system cannot be verified anywhere else.
    /*
     * Tests every target runs.
     *
     * `plans/conformance.md` Part 7 carried this as a gap: the holder claims were asserted on one
     * Java Virtual Machine and claimed for four clients on the strength of shared code. That is a
     * sound argument and it is not evidence -- `FocusManager.clearFocus()` and
     * `Modifier.verticalScroll` are one call each and three implementations.
     *
     * `compose.uiTest` publishes for every target this module has, so `runComposeUiTest` runs where
     * the code runs rather than only where it is convenient.
     */
    commonTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    /*
     * Tests that render, on every target that can render.
     *
     * Not `commonTest`, and the reason is Android: its **unit** test target runs against a stubbed
     * framework jar with no real Android in it, so a Compose UI test there dies on
     * `android.os.Build.FINGERPRINT is null` before it composes anything. Android renders in its
     * *instrumented* tests, which is where this project's accessibility drill already lives.
     *
     * So this is an intermediate source set that the three targets which can host a composition
     * out of process depend on, and Android's unit tests do not. The alternative -- Robolectric --
     * would be a third rendering environment to trust, to run tests whose whole purpose is being
     * run where the code runs.
     */
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
    val wasmJsTestSourceSet = getByName("wasmJsTest")
    wasmJsTestSourceSet.dependsOn(renderTest)

    val wasmJsTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    jvmMain.get().dependsOn(jvmAndroidMain)
    androidMain.get().dependsOn(jvmAndroidMain)
    iosMain.get().dependsOn(ziplineMain)

    // Generated host bindings. Not committed; regenerated from the surface on every build.
    //
    // Wired through the producing task rather than as a bare directory, so every consumer of this
    // source set inherits the dependency. `dependsOn` on the compilations was enough until
    // publishing asked for a **sources jar**, which packages the directory rather than compiling
    // it — and Gradle refuses a task that reads another's output without saying so, which is the
    // right refusal. Adding the jars to the `dependsOn` list would have fixed this one and left
    // the next consumer to find the same wall.
    commonMain.get().kotlin.srcDir(
      project(":dogwood-codegen").tasks.named("generateDesignSystem").map {
        rootProject.layout.buildDirectory.dir("generated/dogwood/host").get()
      },
    )

    commonMain {
      dependencies {
        // `dogwood-wire`, not `dogwood-protocol`: the protocol module declares Zipline services
        // and cannot compile for WebAssembly. What the core needs is the grammar, which is
        // transport-free by construction. Layer 5 ADR-041.
        api(project(":dogwood-wire"))
        // One binding implementation reaches Android, desktop, Web and iOS, which is the whole
        // reason the host renders through Compose Multiplatform rather than native widgets.
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material3)
        api(compose.materialIconsExtended)
        api(compose.ui)
        implementation(libs.coroutines.core)
        implementation(libs.okio)
        api(libs.coil.compose)
      }
    }
    iosMain {
      dependencies {
        // Coil's multiplatform network fetcher, so `AsyncImage(url)` fetches a picture on iOS
        // exactly as it does on Android. Ktor's Darwin engine is NSURLSession underneath.
        implementation(libs.coil.network.ktor)
        implementation(libs.ktor.client.darwin)
      }
    }
    androidMain {
      dependencies {
        implementation(libs.coroutines.android)
      }
    }
    iosTest {
      dependencies {
        implementation(kotlin("test"))
        // The dispatcher tests drive real coroutines across a real thread; `runTest` is how you
        // await one without blocking the test thread the dispatcher is not running on.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
      }
    }
    jvmTest {
      dependencies {
        implementation(kotlin("test"))
        // An in-memory filesystem, so the saved-state store's refusals -- oversized, stale,
        // undecodable -- are tested without leaving files on whoever runs the build.
        implementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
        // The oracle for the vendored plural rules, and a **test-only** dependency: it is the
        // same Unicode Common Locale Data Repository (CLDR) data the platform ships, so the
        // vendored table can be compared against it rather than trusted. Nothing in any shipped
        // artifact depends on it -- International Components for Unicode for Java (ICU4J) is a
        // large library and putting it in an application to answer one question would be a
        // strange trade.
        implementation("com.ibm.icu:icu4j:77.1")
        // A real composition, on the host, in a unit test. Until this existed the host half of
        // every decision was demonstrated on a device and asserted by construction; ADR-009 had
        // to say so about pixel identity. Desktop Compose Multiplatform runs on the Java Virtual
        // Machine, which is where these tests already are.
        @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
        implementation(compose.uiTest)
        implementation(compose.desktop.currentOs)
      }
    }
  }
}

// SignatureTest asserts on the manifest the build actually produces, not on a fixture, so a
// change that silently stopped signing would fail rather than pass quietly.
tasks.named("jvmTest") {
  dependsOn(":samples:slice-guest:jsBrowserProductionWebpackZipline")
}

android {
  namespace = "dev.dogwood.host"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
  dependsOn(":dogwood-codegen:generateDesignSystem")
}

// Zipline's API validator reads the same generated sources but is not a Kotlin compilation task,
// so the rule above does not reach it. Without this, `gradle build` fails with a missing implicit
// dependency the moment the generated directory is stale -- which is every clean checkout.
tasks.matching { it.name.contains("ZiplineApi") }.configureEach {
  dependsOn(":dogwood-codegen:generateDesignSystem")
}


