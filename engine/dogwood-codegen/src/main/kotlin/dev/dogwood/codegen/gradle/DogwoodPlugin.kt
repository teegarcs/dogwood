/*
 * Project Dogwood -- the generator as a Gradle plugin.
 *
 * [ADR-046](../../../../../../../../adrs/layer-5/ADR-046-a-product-registers-its-own-segment.md)
 * made a product's own dictionary segment work and left the last step undone: the generator was an
 * internal Gradle project, so the sample consumed it as `project(":dogwood-codegen")` and nobody
 * outside this repository could consume it at all.
 *
 * What stood between them was not capability, it was fourteen command-line arguments. A product had
 * to know that a lock path is passed with `--lock`, that `--wire-out` must be omitted or the engine's
 * version vector is silently overwritten, and which of the three package options means which half
 * of the boundary. None of that is a decision a product should be making; only two things are --
 * **what the segment is called and which identifier it owns**.
 *
 * So the plugin's job is to take those two, derive the rest, and register the outputs as source
 * directories on the compilations that need them. What remains configurable is what genuinely
 * varies; what is derivable is derived.
 */
package dev.dogwood.codegen.gradle

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity

/**
 * One dictionary segment, as a product declares it.
 *
 * @property segmentId **the one decision that is permanent.** It is half of every widget tag this
 *   segment will ever ship, so changing it is indistinguishable, on a client one release behind,
 *   from every component being replaced at once. 0 and 1 are Dogwood's.
 */
abstract class DogwoodSegmentSpec @Inject constructor(val name: String, objects: ObjectFactory) {

  /** What a guest calls this segment. Defaults to [name]; dot it if you prefer. */
  val wireName: Property<String> = objects.property(String::class.java).convention(name)

  val segmentId: Property<Int> = objects.property(Int::class.java)

  /** What this client implements. Raise it when you add a component; the lock insists. */
  val version: Property<Int> = objects.property(Int::class.java).convention(1)

  /** Where the surface files are. Read as source and never compiled. */
  val surfaceDir: Property<String> = objects.property(String::class.java).convention("surface")

  /** Where the generated guest stubs go. This is the package guest code imports. */
  val guestPackage: Property<String> = objects.property(String::class.java)

  /** Where the generated host bindings go, and where your `*Impl` functions live. */
  val hostPackage: Property<String> = objects.property(String::class.java)

  /**
   * Where the `*Impl` functions live, if not [hostPackage].
   *
   * Defaulted rather than required, because they are the same package in every arrangement anybody
   * has wanted so far and a third package option is a third thing to get wrong.
   */
  val implPackage: Property<String> = objects.property(String::class.java)

  /**
   * Where to write this segment's component reference, if you want one.
   *
   * A Markdown page listing every component, its tags, its properties and which of them govern an
   * affordance -- generated from the same parse as the bindings, so it cannot disagree with them.
   * Off by default: it is a document, and a product that keeps its documentation elsewhere should
   * not find one appearing in its tree.
   *
   * Point it somewhere committed rather than into `build/`. A reference nobody can open is not a
   * reference, which is the same reasoning that puts the lock beside the surface.
   */
  val referenceFile: Property<String> = objects.property(String::class.java)

  /**
   * Whether a component the rule cannot bind is a warning rather than a failure.
   *
   * Off, and off in anything that ships. A rejected component is left out of both ends of the
   * boundary, so with this on a surface can declare a component nobody can call and the build stays
   * green -- which is how Umbra's stepper went unbindable for as long as it existed. On is for one
   * situation: auditing a surface being written, where seeing every rejection at once is worth more
   * than fixing them one build at a time.
   */
  val allowUnbindable: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}

/** The `dogwood { }` block. */
abstract class DogwoodExtension @Inject constructor(objects: ObjectFactory) {
  val segments = objects.domainObjectContainer(DogwoodSegmentSpec::class.java) { name ->
    objects.newInstance(DogwoodSegmentSpec::class.java, name, objects)
  }

  /** Declares a segment. The name is the Kotlin identifier the generated declarations use. */
  fun segment(name: String, configure: DogwoodSegmentSpec.() -> Unit) {
    segments.create(name).apply(configure)
  }
}

/**
 * Applies the generator to a project.
 *
 * Registers one task per segment, and adds the generated host bindings to every Kotlin compilation
 * in the project. The **guest** stubs are deliberately not added anywhere: they belong to a
 * different artifact -- a Kotlin/JavaScript library the guest depends on -- and a plugin that
 * guessed which compilation wanted them would be wrong for every product that keeps its guest in
 * its own module, which is all of them.
 */
class DogwoodPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    val extension = project.extensions.create("dogwood", DogwoodExtension::class.java)

    extension.segments.all { spec ->
      val outputs: org.gradle.api.provider.Provider<Directory> =
        project.layout.buildDirectory.dir("generated/dogwood/${spec.name}")

      val task = project.tasks.register(
        "generate${spec.name.replaceFirstChar { it.uppercase() }}",
        JavaExec::class.java,
      ) { exec ->
        exec.group = "build"
        exec.description = "Generates Dogwood guest stubs, host bindings and the dictionary " +
          "for segment '${spec.name}'"
        exec.mainClass.set("dev.dogwood.codegen.MainKt")
        exec.classpath = project.configurations.getByName(GENERATOR_CONFIGURATION)

        val surface = project.file(spec.surfaceDir.get())
        exec.inputs.dir(surface).withPathSensitivity(PathSensitivity.RELATIVE)
        exec.outputs.dir(outputs)
        // Declared as an output, so deleting the reference regenerates it rather than leaving the
        // task up to date with a file that is no longer there.
        spec.referenceFile.orNull?.let { exec.outputs.file(project.file(it)) }

        exec.argumentProviders.add {
          val root = outputs.get().asFile
          val segmentId = spec.segmentId.orNull
            ?: error(
              "dogwood: segment '${spec.name}' has no segmentId. It is half of every widget tag " +
                "this segment will ever ship and it is permanent; 0 and 1 are Dogwood's, so pick " +
                "2 or above.",
            )
          val guestPackage = spec.guestPackage.orNull
            ?: error("dogwood: segment '${spec.name}' has no guestPackage")
          val hostPackage = spec.hostPackage.orNull
            ?: error("dogwood: segment '${spec.name}' has no hostPackage")
          listOf(
            "--source", surface.absolutePath,
            "--segment", spec.name,
            "--wire-name", spec.wireName.get(),
            "--segment-id", segmentId.toString(),
            "--version", spec.version.get().toString(),
            "--guest-package", guestPackage,
            "--host-package", hostPackage,
            "--impl-package", spec.implPackage.getOrElse(hostPackage),
            "--guest-out", java.io.File(
              root, "guest/${guestPackage.replace('.', '/')}/${fileName(spec.name)}Stubs.kt",
            ).absolutePath,
            "--host-out", java.io.File(
              root, "host/${hostPackage.replace('.', '/')}/${fileName(spec.name)}Bindings.kt",
            ).absolutePath,
            "--dictionary-out",
            java.io.File(root, "dictionary/${spec.wireName.get()}.json").absolutePath,
            // Beside the surface, and committed. A product's tags are as permanent as Dogwood's,
            // and the lock is the record that they never moved.
            "--lock",
            java.io.File(surface, "${spec.wireName.get()}.lock.json").absolutePath,
          ) + (
            spec.referenceFile.orNull
              ?.let { listOf("--docs-out", project.file(it).absolutePath) }
              .orEmpty()
            ) + (
            if (spec.allowUnbindable.get()) listOf("--allow-unbindable", "true") else emptyList()
            )
        }
      }

      // The host bindings compile with the product's own code, so they go on every Kotlin
      // compilation. The guest stubs do not: see the class comment.
      project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        project.addHostSources(outputs)
      }
      project.plugins.withId("org.jetbrains.kotlin.jvm") {
        project.addHostSources(outputs)
      }
      project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java)
        .configureEach { it.dependsOn(task) }
    }
  }

  private companion object {
    /**
     * Where the generator itself comes from.
     *
     * Its own configuration rather than the project's compile classpath, because the generator is a
     * *tool* — it runs before compilation and nothing a product writes should link against it.
     */
    const val GENERATOR_CONFIGURATION = "dogwoodGenerator"

    fun fileName(segment: String) = segment.replaceFirstChar { it.uppercase() }
  }
}

private fun Project.addHostSources(outputs: org.gradle.api.provider.Provider<Directory>) {
  val kotlin = extensions.findByName("kotlin") ?: return
  when (kotlin) {
    is org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension ->
      kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(outputs.map { it.dir("host") })
    is org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension ->
      kotlin.sourceSets.getByName("main").kotlin.srcDir(outputs.map { it.dir("host") })
    else -> Unit
  }
}
