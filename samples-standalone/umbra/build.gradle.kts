/*
 * Plugin versions, declared once and applied by the modules that need them.
 *
 * Every version here is a decision the ADOPTER makes, which is the point of this file existing in
 * the sample: the engine was built with Kotlin 2.3.20, Compose Multiplatform 1.10.3 and Zipline
 * 1.27.0, and a product's safest position today is to match them -- see the supported-versions
 * table in `docs/getting-started.md`.
 */
plugins {
  kotlin("jvm") version "2.3.20" apply false
  kotlin("multiplatform") version "2.3.20" apply false
  kotlin("plugin.serialization") version "2.3.20" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
  id("org.jetbrains.compose") version "1.10.3" apply false
  id("app.cash.zipline") version "1.27.0" apply false
  id("dev.dogwood.codegen") version "0.1.0" apply false
  // Android, for the `:android` compile probe. Declared here with the others because Gradle refuses
  // a versioned plugin request in a subproject once the plugin is on the build's classpath.
  id("com.android.library") version "8.13.2" apply false
  kotlin("android") version "2.3.20" apply false
}
