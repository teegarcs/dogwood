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
        implementation(project(":dogwood-compose"))
      }
    }
  }
}

zipline {
  mainFunction.set("dev.dogwood.slice.main")
  optimizeForSmallArtifactSize()
}
