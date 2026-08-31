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

include(":dogwood-protocol")
include(":dogwood-compose")
include(":dogwood-host")
include(":samples:slice-guest")
include(":samples:slice-desktop")
include(":samples:slice-android")
