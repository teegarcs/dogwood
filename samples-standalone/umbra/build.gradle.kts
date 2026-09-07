/*
 * Umbra's design system, built entirely against published Dogwood artifacts.
 *
 * The whole file is the point. There is no `project(":dogwood-codegen")` here and no path to one:
 * the plugin is applied by identifier, the generator arrives as a dependency, and the runtime comes
 * from a repository. If this builds, a product outside this repository can use Dogwood.
 */
plugins {
  kotlin("jvm") version "2.3.20"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
  // Compose Multiplatform's own plugin, which is what maps `compose.material3` onto the right
  // artifact for the platform being built. A product using Compose applies it anyway.
  id("org.jetbrains.compose") version "1.10.3"
  id("dev.dogwood.codegen") version "0.1.0"
}

kotlin { jvmToolchain(21) }

// The generator is a *tool*: it runs before compilation, and nothing Umbra writes links against it.
val dogwoodGenerator by configurations.creating

dependencies {
  dogwoodGenerator("dev.dogwood:dogwood-codegen:0.1.0")

  implementation("dev.dogwood:dogwood-host:0.1.0")
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.desktop.currentOs)
}

/*
 * Two decisions, and the plugin derives the rest.
 *
 * Compare this with what the same thing looked like before the plugin: fourteen command-line
 * arguments, three of them package names that had to be told apart, one output path per artifact,
 * and a `--wire-out` that had to be *omitted* or the engine's version vector was silently
 * overwritten with this segment's.
 */
dogwood {
  segment("umbraDesignSystem") {
    wireName.set("umbra.designsystem")
    // 0 and 1 are Dogwood's; 2 is Acme's in the in-repository sample. Umbra takes 3.
    segmentId.set(3)
    version.set(1)
    guestPackage.set("dev.umbra.guest")
    hostPackage.set("dev.umbra.design")
    // The component reference, generated from the same parse as the bindings. Committed rather
    // than left in `build/`, for the reason the lock is: a file nobody can open is not a record.
    referenceFile.set("REFERENCE.md")
  }
}
