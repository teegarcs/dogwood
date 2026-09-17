/*
 * Project Dogwood -- the guest-side plugin, which is one task.
 *
 * `io.github.teegarcs.dogwood.guest` applies the authoring check Layer 1 requires and nothing else. That
 * check has two halves and therefore two tasks: `dogwoodGuestCheck` reads the module's source for
 * forbidden calls, and `dogwoodGuestClasspathCheck` reads its resolved dependency graph for
 * forbidden artifacts. The second exists because the first cannot see an artifact that arrived
 * transitively, which is the half ADR-050 §4 deferred. Both join `check`.
 *
 * It is a separate plugin from `io.github.teegarcs.dogwood.codegen` because the two are applied to different modules by
 * different people: a product's design-system module generates bindings, and a product's *guest*
 * module is checked. A module that did both would be a module whose guest code can see its host
 * code, which is the confusion the whole architecture exists to prevent.
 */
package dev.dogwood.codegen.gradle

import dev.dogwood.codegen.guest.ResolvedModule
import dev.dogwood.codegen.guest.checkGuestClasspath
import dev.dogwood.codegen.guest.checkGuestSource
import dev.dogwood.codegen.guest.guestCheckFailure
import dev.dogwood.codegen.guest.guestClasspathFailure
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
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

/**
 * Fails the build when a guest *resolves* an artifact Layer 1 forbids, called or not.
 *
 * The half `adrs/layer-5/ADR-050-the-authoring-check.md` §4 deferred. A source scan cannot see an
 * artifact that arrived transitively, and that is the failure worth catching: a guest that depends
 * on a helper library which itself depends on `animation-core` has every frame-clock Application
 * Programming Interface (API) resolvable by name, with nothing in its own source to show for it
 * until the day somebody imports one.
 *
 * It reads the **resolved** graph rather than the declarations in the build file, because the
 * declarations are the set that was never the problem. `dev.dogwood.codegen.guest.GuestClasspath`
 * holds the list and the decision; this class is the part that has to know about Gradle.
 */
abstract class DogwoodGuestClasspathCheckTask : DefaultTask() {

  /**
   * Configuration name to the root of its resolved graph.
   *
   * `@Internal` rather than `@Input`: a `ResolvedComponentResult` is a graph, not a value, and the
   * task is never up to date anyway -- resolution can change without a file in this module
   * changing, which is exactly the case this check exists for.
   */
  @get:Internal
  abstract val roots: MapProperty<String, ResolvedComponentResult>

  @TaskAction
  fun check() {
    val configurations = roots.get()
    // A check that inspects nothing must say so rather than pass. Silence here would read as "no
    // forbidden artifacts", which is a different claim from "no classpath was found".
    if (configurations.isEmpty()) {
      throw GradleException(
        "Dogwood: the guest classpath check found no resolvable runtime classpath in " +
          "${project.path}. It inspected nothing, which is not the same as finding nothing. " +
          "Apply io.github.teegarcs.dogwood.guest to the module that builds the guest.",
      )
    }

    val violations = configurations.entries.sortedBy { it.key }.flatMap { (name, root) ->
      checkGuestClasspath(name, flattenResolution(root))
    }
    if (violations.isNotEmpty()) throw GradleException(guestClasspathFailure(violations))

    val modules = configurations.values.sumOf { flattenResolution(it).size }
    logger.lifecycle(
      "dogwood: ${configurations.size} guest configuration(s) checked, $modules resolved module(s), " +
        "nothing forbidden",
    )
  }
}

/**
 * Every module the resolution reached, each carrying the route it took to get there.
 *
 * Breadth-first, so the recorded route is the shortest one -- a banned artifact pulled in by two
 * different dependencies should be reported against the nearer of them, since that is the one whose
 * `exclude` is easiest to reason about. A module already seen is not walked again, which is also
 * what keeps a dependency cycle from becoming an infinite loop.
 *
 * A project dependency has no coordinate, so it contributes a step to the route and nothing to the
 * list: `:samples:slice-screens` is not an artifact anybody can ban, but it is very much a way for
 * one to arrive.
 */
internal fun flattenResolution(root: ResolvedComponentResult): List<ResolvedModule> {
  val modules = mutableListOf<ResolvedModule>()
  val seen = mutableSetOf<String>()
  val queue = ArrayDeque<Pair<ResolvedComponentResult, List<String>>>()
  queue += root to emptyList()
  while (queue.isNotEmpty()) {
    val (component, path) = queue.removeFirst()
    for (dependency in component.dependencies.filterIsInstance<ResolvedDependencyResult>()) {
      val selected = dependency.selected
      val id = selected.id
      val step = if (id is ModuleComponentIdentifier) {
        "${id.group}:${id.module}:${id.version}"
      } else {
        id.displayName
      }
      if (!seen.add(step)) continue
      if (id is ModuleComponentIdentifier) {
        modules += ResolvedModule(id.group, id.module, id.version, path + step)
      }
      queue += selected to (path + step)
    }
  }
  return modules
}

/**
 * Which configurations are the guest's own runtime.
 *
 * `runtimeClasspath` for a plain Java Virtual Machine (JVM) module, `<target>RuntimeClasspath` for
 * a Kotlin Multiplatform one -- `jsRuntimeClasspath` is the one that matters, since a Zipline guest
 * is Kotlin compiled to JavaScript. Test configurations are excluded on purpose: a test source set
 * is not shipped in a payload, and a guest whose *tests* animate something has not put per-frame
 * state on any device.
 */
internal fun isGuestRuntimeClasspath(name: String): Boolean {
  if (name.contains("test", ignoreCase = true)) return false
  return name == "runtimeClasspath" || name.endsWith("RuntimeClasspath")
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
    val classpathTask = project.tasks.register(
      "dogwoodGuestClasspathCheck",
      DogwoodGuestClasspathCheckTask::class.java,
    ) {
      it.group = "verification"
      it.description = "Rejects guest dependencies that put a forbidden API one import away"
      // Never up to date. A resolution can change without a file in this module changing -- a
      // version range moving, a transitive dependency added upstream -- and that is the case this
      // check exists for.
      it.outputs.upToDateWhen { false }
    }

    // After evaluation, because the configurations a Kotlin Multiplatform module resolves do not
    // exist until its targets are declared. `rootComponent` is a provider, so nothing is resolved
    // here: the graph is produced when the task runs.
    project.afterEvaluate { evaluated ->
      val configurations = evaluated.configurations
        .filter { it.isCanBeResolved && isGuestRuntimeClasspath(it.name) }
      classpathTask.configure { task ->
        for (configuration in configurations) {
          task.roots.put(configuration.name, configuration.incoming.resolutionResult.rootComponent)
        }
      }
    }

    // Part of `check`, so it runs where a person expects a rule to be enforced rather than only
    // when somebody remembers to ask for it.
    project.plugins.withId("base") {
      project.tasks.named("check") {
        it.dependsOn(task)
        it.dependsOn(classpathTask)
      }
    }
  }
}
