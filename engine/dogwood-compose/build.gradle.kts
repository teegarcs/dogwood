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
    // Tests run on Node. The gate conditions this module has to satisfy -- wrapper scoping and
    // node identity across a reorder -- are properties of composition and of the applier, so
    // they must run where the real Compose runtime runs, which is Kotlin/JavaScript.
    nodejs()
  }

  sourceSets {
    jsTest {
      dependencies {
        implementation(kotlin("test"))
      }
    }
    jsMain {
      dependencies {
        api(project(":dogwood-protocol"))
        // The genuine Compose runtime, compiled to JavaScript. This is what makes `remember`,
        // `mutableStateOf`, `derivedStateOf` and recomposition behave as a developer expects,
        // with no Dogwood-specific protocol for any of them.
        api(libs.compose.runtime.js)
        implementation(libs.compose.runtime.saveable.js)
        implementation(libs.coroutines.core)
      }
    }
  }
}
