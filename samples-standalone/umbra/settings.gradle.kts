/*
 * Umbra -- a product consuming Dogwood from outside the repository.
 *
 * This is a **separate Gradle build**. It has no `includeBuild`, no project dependency and no path
 * into `engine/`; everything it uses is resolved from a repository, which is the only way to find
 * out whether Dogwood is consumable rather than merely modular.
 *
 * `mavenLocal()` is what a developer has before anything is published anywhere. A real product
 * would point at the repository the artifacts are actually deployed to; that is a deployment
 * decision and is tracked in `DECISIONS-FOR-THE-OWNER.md` rather than decided here.
 */
pluginManagement {
  repositories {
    mavenLocal()
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenLocal()
    mavenCentral()
    google()
  }
}

rootProject.name = "umbra"
