plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.zipline)
}

kotlin {
  jvmToolchain(21)

  js(IR) {
    browser()
    binaries.executable()
  }

  sourceSets {
    jsMain {
      dependencies {
        api(project(":protocol"))
        // The real Compose runtime, compiled to JavaScript. This dependency is the whole
        // point of experiment 0.1: runtime-js alone is a large klib and its cost after
        // dead-code elimination and minification is the number Phase 0 must produce.
        implementation(libs.compose.runtime.js)
        implementation(libs.compose.runtime.saveable.js)
        implementation(libs.coroutines.core)
        implementation(libs.serialization.json)
      }
    }
  }
}

zipline {
  mainFunction.set("dev.dogwood.guest.main")
  // Production configuration, per experiment 0.1: "linked in production configuration".
  optimizeForSmallArtifactSize()
}
