pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "dogwood"

include(":dogwood-wire")
include(":dogwood-protocol")
include(":dogwood-compose")
include(":dogwood-host")
include(":dogwood-codegen")
include(":samples:slice-guest")
include(":samples:slice-desktop")
include(":samples:slice-android")

// The web profile (Layer 5 ADR-032): the Kotlin/WebAssembly host and its runnable sample.
include(":dogwood-web", ":samples:web-slice")

// The iOS host (roadmap Phase 6).
include(":samples:slice-ios")
