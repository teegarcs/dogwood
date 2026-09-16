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
 * What is deliberately NOT here is a transport to the Central Portal. The `com.vanniktech.maven.publish`
 * plugin is the standard route, and it takes over publication creation for Kotlin Multiplatform
 * modules -- which would replace the hand-configured publications above, including the pinned
 * module names ADR-047 and ADR-071 exist because of. Until somebody has a Central account to test
 * against, the bundle `publishToMavenLocal` produces under `~/.m2/repository/io/github/teegarcs`
 * is what the Portal's manual upload takes, signed and complete.
 */
subprojects {
  plugins.withId("maven-publish") {
    apply(plugin = "signing")

    val gpgKey: String? = System.getenv("DOGWOOD_GPG_KEY")
    val gpgPassphrase: String? = System.getenv("DOGWOOD_GPG_PASSPHRASE")

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
