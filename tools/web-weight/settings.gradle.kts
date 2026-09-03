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
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

rootProject.name = "web-weight"

// One module per configuration being weighed, so each produces its own distribution and the
// difference between them is attributable rather than inferred.
include(":floor")
include(":material")

// Not a page-weight configuration: `:bridge` measures the cost of moving a per-frame tree-diff
// from JavaScript into Kotlin/WebAssembly. See `results/bridge.md`.
include(":bridge")
