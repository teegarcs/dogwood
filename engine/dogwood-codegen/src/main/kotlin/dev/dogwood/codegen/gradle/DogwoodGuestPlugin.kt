/*
 * Project Dogwood -- the guest-side plugin, which is one task.
 *
 * `io.github.teegarcs.dogwood.guest` applies the authoring check Layer 1 requires and nothing else. It is a
 * separate plugin from `io.github.teegarcs.dogwood.codegen` because the two are applied to different modules by
 * different people: a product's design-system module generates bindings, and a product's *guest*
 * module is checked. A module that did both would be a module whose guest code can see its host
 * code, which is the confusion the whole architecture exists to prevent.
 */
package dev.dogwood.codegen.gradle

import dev.dogwood.codegen.guest.checkGuestSource
import dev.dogwood.codegen.guest.guestCheckFailure
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/** Fails the build when guest code calls something the sandbox cannot honestly run. */
abstract class DogwoodGuestCheckTask : DefaultTask() {

  @get:InputFiles
  @get:SkipWhenEmpty
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  @TaskAction
  fun check() {
    val root = project.projectDir
    val violations = sources.files
      .filter { it.isFile && it.extension == "kt" }
      .flatMap { file -> checkGuestSource(file.relativeTo(root).path, file.readText()) }

    if (violations.isNotEmpty()) throw org.gradle.api.GradleException(guestCheckFailure(violations))
    logger.lifecycle("dogwood: ${sources.files.count { it.extension == "kt" }} guest files checked")
  }
}

class DogwoodGuestPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val task = project.tasks.register("dogwoodGuestCheck", DogwoodGuestCheckTask::class.java) {
      it.group = "verification"
      it.description = "Rejects guest code that would tick the boundary every frame"
      // Every Kotlin source directory in the project. Deliberately not "the compilation's sources":
      // generated stubs are sources too and are exactly what the guest is *supposed* to call, so a
      // check aimed at compilations would spend its time reading code nobody wrote.
      it.sources.from(project.fileTree(File(project.projectDir, "src")) { tree ->
        tree.include("**/*.kt")
      })
    }
    // Part of `check`, so it runs where a person expects a rule to be enforced rather than only
    // when somebody remembers to ask for it.
    project.plugins.withId("base") {
      project.tasks.named("check") { it.dependsOn(task) }
    }
  }
}
