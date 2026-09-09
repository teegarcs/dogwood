import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

/*
 * Project Dogwood -- one application, two independently shipped payloads.
 *
 * `plans/adoption-audit.md` B3 recorded that `DogwoodShell` takes a single `manifestUrl`, so its
 * "several experiences" are entry points **within one payload**, and that two teams shipping
 * independently therefore means two shells -- separate caches, separate warm pools, separate release
 * guards -- with the real costs unexamined.
 *
 * This is the examination. A desktop host rather than Android or iOS, for one reason: it is the
 * client where a measurement can be taken repeatedly in seconds, and what is being measured -- an
 * interpreter, a cache, a guard, a dispatcher, per shell -- is host-side and platform-independent.
 * The numbers are recorded in `docs/multi-team.md` with the machine named, and they are what they
 * are: a development host, not a device. That is fine here in a way it is not for the Phase 0
 * budgets, because the question is a *ratio* between one shell and two on the same machine.
 */
plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":dogwood-host"))
  // The checkout payload composes Acme components, so this host has to bind them -- see `main`.
  implementation(project(":samples:product-design-system"))
  implementation(compose.desktop.currentOs)
  implementation(libs.coroutines.core)
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}

compose.desktop {
  application {
    mainClass = "dev.dogwood.two.MainKt"
    // The same eight-megabyte stack every Dogwood host needs: QuickJS composition is deeply
    // recursive and interpreted frames are heavy.
    jvmArgs += listOf("-Xss8m")
    /*
     * The measurement switches, and getting them into the application took three attempts, each of
     * which failed by looking like a hang: the application loaded both payloads, found `measure`
     * false, and waited for a person who was not there.
     *
     *   1. `args("--measure")` on the `run` task -- replaced, because the Compose Desktop plugin
     *      configures that task from this extension afterwards.
     *   2. `jvmArgs("-D…")` on the `run` task -- replaced for the same reason.
     *   3. `System.getProperty(...)` read here -- read from the Gradle *daemon*, which is not
     *      reliably the process the `-D` was typed at.
     *
     * `providers.gradleProperty` is the mechanism that works and the one the rest of this build
     * already uses (`-PdogwoodDeclareSegments`, `-PdogwoodVersion`). It is also the only one of the
     * four that survives Gradle's configuration cache, which is why it is the right answer rather
     * than merely the one that happened to run.
     */
    for (name in listOf("dogwoodMeasure", "dogwoodManifestA", "dogwoodManifestB")) {
      providers.gradleProperty(name).orNull?.let { jvmArgs += "-D$name=$it" }
    }
  }
}

tasks.matching { it.name == "run" }.configureEach {
  this as JavaExec
  workingDir = rootProject.projectDir
}
