plugins {
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinAndroid) apply false
  alias(libs.plugins.kotlinSerialization) apply false
  alias(libs.plugins.composeCompiler) apply false
  alias(libs.plugins.composeMultiplatform) apply false
  alias(libs.plugins.zipline) apply false
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidLibrary) apply false
  alias(libs.plugins.mavenPublishBase) apply false
}

/*
 * Publishing to Maven Central, the half that is engineering.
 *
 * Central requires every artifact to be PGP-signed, and every POM to carry a name, a description,
 * a URL, a licence, a developer and a source-control block. Both are configured here, once, for
 * every module that publishes, rather than six times in six build files -- which is how
 * `dogwood-web` went without any publishing at all for a week (ADR-070).
 *
 * The key is read from the environment and nothing else. Without `DOGWOOD_GPG_KEY` set, signing is
 * not required and `publishToMavenLocal` works exactly as it always has; with it set, every
 * publication is signed and a missing passphrase is a failure rather than an unsigned artifact.
 * The GPG key is NOT the Ed25519 payload key of `OPEN-DECISIONS.md` §6: two keys, two jobs -- one
 * says these bytes came from this publisher, the other says this payload may run on a device.
 *
 * **The transport to the Central Portal is here since 2026-09-16, and it is the `.base` plugin
 * rather than the full one.** `com.vanniktech.maven.publish` -- the full plugin -- takes over
 * publication creation for Kotlin Multiplatform modules, which would replace the hand-configured
 * publications above including the pinned module names ADR-047 and ADR-071 exist because of.
 * `com.vanniktech.maven.publish.base` adds only what this build does not have: the Portal
 * credentials, the bundle assembly, and the upload. It configures no publications and signs
 * nothing -- `signAllPublications()` is deliberately not called, because the `signing` block below
 * already signs every publication from the same environment.
 *
 * What that buys, with no credentials set at all:
 *
 *     ./gradlew publishAllPublicationsToMavenCentralRepository
 *
 * writes, under each module's `build/mavenCentral`, exactly the directory tree the Portal
 * validates -- artifacts, POMs, checksums and (when a key is in the environment) signatures. That
 * tree is what `tools/reference-server/portal-bundle-check.py` opens and grades against the
 * Portal's published requirements. **The Portal itself cannot be contacted without an account**,
 * so the check is on the bundle, and the one step that remains when the account exists is entering
 * `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` and running `publishToMavenCentral`.
 */
subprojects {
  plugins.withId("maven-publish") {
    apply(plugin = "signing")
    apply(plugin = "com.vanniktech.maven.publish.base")

    /*
     * The Portal's credentials, and the one thing about them that is not obvious.
     *
     * The plugin reads the **Gradle properties** `mavenCentralUsername` and `mavenCentralPassword`
     * through `providers.gradleProperty`, and Gradle computes its property set before any build
     * script runs. So a build script cannot turn `MAVEN_CENTRAL_USERNAME` into one -- this was
     * measured rather than assumed: `System.setProperty("org.gradle.project.mavenCentralUsername",
     * ...)` from exactly here left `prepareMavenCentralPublishing` still failing with
     * "mavenCentralUsername not found".
     *
     * Two spellings do work, and both start from the same two secrets:
     *
     *     ORG_GRADLE_PROJECT_mavenCentralUsername="$MAVEN_CENTRAL_USERNAME" ./gradlew publishToMavenCentral
     *     ./gradlew publishToMavenCentral -PmavenCentralUsername="$MAVEN_CENTRAL_USERNAME" ...
     *
     * `docs/operating.md` §5 uses the first. What is here instead is the error a publisher who
     * exported only `MAVEN_CENTRAL_USERNAME` would otherwise have to guess at.
     */
    tasks.matching { it.name == "prepareMavenCentralPublishing" }.configureEach {
      doFirst {
        if (System.getenv("MAVEN_CENTRAL_USERNAME") != null &&
          !project.hasProperty("mavenCentralUsername")
        ) {
          error(
            "MAVEN_CENTRAL_USERNAME is set but the Gradle property is not; Gradle reads its " +
              "properties before this build script runs. Re-run with " +
              "ORG_GRADLE_PROJECT_mavenCentralUsername=\"\$MAVEN_CENTRAL_USERNAME\" and " +
              "ORG_GRADLE_PROJECT_mavenCentralPassword=\"\$MAVEN_CENTRAL_PASSWORD\" in the " +
              "environment. See docs/operating.md section 5.",
          )
        }
      }
    }

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
      // The Central Portal, not the retired OSSRH staging API. `automaticRelease = false`: the
      // upload lands as a deployment somebody looks at and releases, because the one property
      // Central does not have is an undo.
      publishToMavenCentral(automaticRelease = false)
    }

    /*
     * The same bundle, assembled locally, with no credentials and no network.
     *
     * `publishToMavenCentral` cannot run without an account: its first task refuses, by design,
     * rather than building something it could not upload. That would leave the thing this project
     * most needs to check -- **is the bundle actually complete?** -- checkable only by whoever
     * eventually has the account, which is the wrong order. So every publication is also published
     * to a plain file repository, which is what a Maven repository layout *is*: the identical
     * artifacts, POMs, checksums and signatures under `io/github/teegarcs/...`.
     *
     *     ./gradlew assembleMavenCentralBundle
     *     python3 tools/reference-server/portal-bundle-check.py
     *
     * The second opens the tree and grades it against the Portal's published requirements. What it
     * cannot do is ask the Portal, and it says so.
     */
    extensions.configure<org.gradle.api.publish.PublishingExtension> {
      repositories {
        maven {
          name = "mavenCentralBundle"
          url = uri(rootProject.layout.buildDirectory.dir("publishing/mavenCentralBundle"))
        }
      }
    }

    val gpgKey: String? = System.getenv("DOGWOOD_GPG_KEY")
    val gpgPassphrase: String? = System.getenv("DOGWOOD_GPG_PASSPHRASE")

    /*
     * A sources jar on the one module that does not get one for free.
     *
     * Central requires a `-sources.jar` beside every jar-packaged artifact. Kotlin Multiplatform
     * publishes one per target automatically, so five of the six publishing modules already had
     * them -- and `dogwood-codegen`, which is an ordinary Java Virtual Machine project with the
     * Gradle plugin development plugin, did not. Nobody had noticed because nothing had ever
     * opened the bundle: `tools/reference-server/portal-bundle-check.py` found it on its first
     * run, as `M2`, with thirty-five coordinates green and one red.
     *
     * Guarded on the `java` extension existing rather than on the project name, so the next
     * Java-Virtual-Machine module to publish inherits the fix instead of repeating the defect.
     */
    extensions.findByType<JavaPluginExtension>()?.withSourcesJar()

    // An empty javadoc jar, because Central's validation requires one on every publication and
    // Kotlin produces none. Standard practice for Kotlin libraries on Central, and honest: the
    // reference is `docs/api/`, generated from the surface, not a javadoc tree.
    val javadocJar = tasks.register<Jar>("dogwoodJavadocJar") {
      archiveClassifier.set("javadoc")
      archiveBaseName.set("${project.name}-javadoc-placeholder")
    }

    extensions.configure<org.gradle.api.publish.PublishingExtension> {
      publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
        // A plugin marker publication is a POM and nothing else; giving it a jar is an error.
        if (!name.contains("PluginMarker")) artifact(javadocJar)
        pom {
          name.set("Dogwood ${project.name}")
          description.set(
            project.description
              ?: "Project Dogwood, ${project.name}: server-driven Compose via a generated binding.",
          )
          url.set("https://github.com/teegarcs/dogwood")
          licenses {
            license {
              name.set("Apache-2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
          }
          developers {
            developer {
              id.set("teegarcs")
              name.set("Clinton Teegarden")
              url.set("https://github.com/teegarcs")
            }
          }
          scm {
            url.set("https://github.com/teegarcs/dogwood")
            connection.set("scm:git:https://github.com/teegarcs/dogwood.git")
            developerConnection.set("scm:git:git@github.com:teegarcs/dogwood.git")
          }
        }
      }
    }

    extensions.configure<org.gradle.plugins.signing.SigningExtension> {
      isRequired = gpgKey != null
      if (gpgKey != null) {
        useInMemoryPgpKeys(gpgKey, gpgPassphrase ?: "")
        sign(extensions.getByType<org.gradle.api.publish.PublishingExtension>().publications)
      }
    }

    // Gradle 8 refuses a publish task that consumes a sign task's output without an ordering, and
    // Kotlin Multiplatform publishes several publications from one module that sign each other's
    // metadata. Every publish waits for every signature.
    tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
      mustRunAfter(tasks.withType<org.gradle.plugins.signing.Sign>())
    }
  }
}

/*
 * One task that assembles the whole bundle, because the Portal takes one upload rather than seven.
 *
 * A publisher running this module by module would upload seven deployments and have to release
 * them in dependency order or watch the validation fail on unresolvable parents. The Portal's
 * unit is a single archive of a repository layout; this produces that archive.
 */
val publishingModules = listOf(
  "dogwood-wire",
  "dogwood-protocol",
  "dogwood-compose",
  "dogwood-host",
  "dogwood-web",
  "dogwood-material3",
  "dogwood-codegen",
)

val assembleMavenCentralBundle by tasks.registering(Zip::class) {
  group = "publishing"
  description = "Assembles the Central Portal bundle locally, with no credentials and no network."
  for (module in publishingModules) {
    dependsOn(":$module:publishAllPublicationsToMavenCentralBundleRepository")
  }
  from(layout.buildDirectory.dir("publishing/mavenCentralBundle"))
  // Gradle writes `maven-metadata.xml` for a file repository, and a Portal bundle is artifacts
  // only: an unsigned file in the archive is a validation failure over something nobody asked for.
  exclude("**/maven-metadata.xml*")
  destinationDirectory.set(layout.buildDirectory.dir("publishing"))
  archiveFileName.set("dogwood-central-bundle.zip")
}
