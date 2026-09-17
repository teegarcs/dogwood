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
include(":dogwood-compose-preview") // the same guest API, delegating to real Compose on the JVM (Group 4)
include(":dogwood-host")
include(":dogwood-codegen")
// The Material 3 tier, generated from the library's own sources (plans/generator-v2.md). Its own
// module so a host -- and the web page-weight measurement -- can leave it out.
include(":dogwood-material3")
// The foundation, layout and ui tiers, generated the same way and kept in ONE module: they share a
// classpath, they share the page-weight decision registering a tier makes on the web, and a host
// that wants either of the other two wants the layout one. The module's build file says more.
include(":dogwood-foundation")
// The sample screens, shared by every guest entry point. See `samples/slice-screens`.
include(":samples:slice-screens")
include(":samples:slice-guest")
// A second, independently built and signed payload; see `docs/multi-team.md`.
include(":samples:second-guest")
// One application hosting both, so the cost of a second shell can be measured.
include(":samples:two-payloads")
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
include(":samples:ios-embed",
  ":samples:slice-ios")
