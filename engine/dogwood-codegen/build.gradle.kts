plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    // The compiler's parser entry points are marked as K1 API. Parsing declarations is exactly
    // what this generator needs; the K2 analysis API is a much larger dependency for no gain
    // until the generator needs resolved types rather than declaration text.
    optIn.add("org.jetbrains.kotlin.K1Deprecation")
  }
}

dependencies {
  implementation(libs.kotlin.compiler.embeddable)
  implementation(libs.serialization.json)
  testImplementation(kotlin("test"))
}
