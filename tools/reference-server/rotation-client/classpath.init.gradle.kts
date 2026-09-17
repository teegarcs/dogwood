/*
 * Project Dogwood -- printing the Zipline classpath without editing a build file.
 *
 * `rotation-client/RotationClient.java` compiles against `app.cash.zipline.loader.ManifestVerifier`
 * and has to resolve it the same way the host does: the pinned version in
 * `engine/gradle/libs.versions.toml`, not whatever a `find` over the Gradle cache turns up first.
 *
 * An initialisation script is how a tool asks a build a question without becoming part of it. This
 * registers one read-only task on `:dogwood-host` and changes nothing else; the engine build is
 * identical whether or not this file exists, which is the property that matters -- a drill that
 * modified the build it is measuring would be measuring itself.
 */
gradle.projectsEvaluated {
  val host = gradle.rootProject.project(":dogwood-host")
  host.tasks.register("dogwoodPrintZiplineClasspath") {
    group = "help"
    description = "Prints the Java Virtual Machine runtime classpath of :dogwood-host."
    doLast {
      println("CLASSPATH=" + host.configurations.getByName("jvmRuntimeClasspath").asPath)
    }
  }
}
