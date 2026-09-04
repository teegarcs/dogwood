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

rootProject.name = "dogwood-phase0"

include(":protocol")
include(":guest")
include(":host-core")
include(":host-jvm")
include(":host-android")

// The iOS runner (roadmap Phase 6 step 3). Kotlin/Native, no user interface: it prints its report.
include(":host-ios")
