/*
 * Umbra on Android -- the platform the standalone proof did not cover.
 *
 * `:app` is a desktop application, and for as long as it was the only consumer outside the engine's
 * own build, **the flagship platform's artifact path was never exercised by anything**. That was not
 * a theoretical gap. Three engine modules declared `androidTarget` and none of them called
 * `publishLibraryVariants`, so `publishToMavenLocal` produced no `androidJvm` variant at all and an
 * Android consumer would have resolved `dogwood-host-jvm` -- Java 21 class files, against the
 * `JVM_11` those same modules asked for -- silently, because that fallback is legal under Kotlin
 * Multiplatform's platform-compatibility rule. It was found by an independent grading run reading
 * the published module metadata, ten days into the project's life and after two clients had shipped
 * conformance evidence. See `tools/framework-grade/results/2026-09-09-run3.md`.
 *
 * **What this module proves, and what it does not.** It resolves `io.github.teegarcs:dogwood-host` from a
 * repository in an *Android* build and compiles real host code against it, so the variant exists,
 * is selected, and its API is reachable. It does **not** render: there is no device in this check
 * and the verdict is a compile. `tools/ios-embed-check/run.sh` states its own limit the same way and
 * for the same reason -- a bounded proof that says what it is beats an unbounded one that does not.
 * The render is covered on Android by the conformance drills against `samples/slice-android`.
 */
plugins {
  // Versions live in the root build with every other adopter-chosen version.
  id("com.android.library")
  kotlin("android")
}

android {
  namespace = "dev.umbra.android"
  compileSdk = 36
  defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(21) }

dependencies {
  // Resolved from a repository by an Android build, with no path into the engine. That sentence is
  // the whole test.
  implementation("io.github.teegarcs:dogwood-host:0.1.0")
  // And the product's own design system, now multiplatform: its generated bindings and the
  // `@Implementation` target they call have to compile for Android too, or a product's components
  // are placeholders on the platform that ships first.
  implementation(project(":design"))
}
