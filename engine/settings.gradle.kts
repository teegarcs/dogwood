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
// The sample screens, shared by every guest entry point. See `samples/slice-screens`.
include(":samples:slice-screens")
include(":samples:slice-guest")
include(":samples:slice-desktop")
include(":samples:slice-android")

// A product's own design system, in a package the engine has never heard of. It is what proves
// the registration mechanism is a mechanism rather than a special case with one caller.
include(":samples:product-design-system")

// The web profile (Layer 5 ADR-032): the Kotlin/WebAssembly host and its runnable sample.
include(":dogwood-web", ":samples:web-slice")

// The real Kotlin/Compose guest, compiled for a Web Worker. Same screens as the mobile payload.
include(":samples:web-guest")

// The iOS host (roadmap Phase 6).
include(":samples:slice-ios")
