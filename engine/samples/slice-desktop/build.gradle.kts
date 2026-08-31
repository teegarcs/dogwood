import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":dogwood-host"))
  implementation(compose.desktop.currentOs)
  implementation(libs.coroutines.core)
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, "app.cash.zipline:zipline-kotlin-plugin:${libs.versions.zipline.get()}")
}

compose.desktop {
  application {
    mainClass = "dev.dogwood.slice.desktop.MainKt"
    // QuickJS composition is deeply recursive and interpreted frames are heavy; Zipline expects
    // callers to use an eight-megabyte stack.
    jvmArgs += listOf("-Xss8m")
    nativeDistributions { targetFormats(TargetFormat.Dmg) }
  }
}

// The Compose desktop plugin registers `run` late, so configure it once it exists.
tasks.matching { it.name == "run" }.configureEach {
  this as JavaExec
  // The guest is resolved relative to the build root, not this module.
  workingDir = rootProject.projectDir
  dependsOn(":samples:slice-guest:jsBrowserProductionWebpackZipline")
}
