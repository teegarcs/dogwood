/*
 * Project Dogwood -- the generator's command-line entry point.
 *
 * A standalone tool rather than a Kotlin Symbol Processing processor, per Layer 5 ADR-002:
 * neither Zipline nor Redwood uses symbol processing for its bridge, and the generator must run
 * over sources it does not compile.
 */
package dev.dogwood.codegen

import java.io.File

fun main(args: Array<String>) {
  val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it[1] }
  fun required(name: String) = options[name] ?: error("missing --$name")

  val sourceDir = File(required("source"))
  val segmentName = required("segment")
  val segmentId = required("segment-id").toInt()
  val version = required("version").toInt()
  val guestOut = File(required("guest-out"))
  val hostOut = File(required("host-out"))
  val dictionaryOut = File(required("dictionary-out"))
  val guestPackage = required("guest-package")
  val hostPackage = required("host-package")
  val implementationPackage = required("impl-package")

  val sources = sourceDir.walkTopDown().filter { it.extension == "kt" }.toList()
  require(sources.isNotEmpty()) { "no Kotlin sources under $sourceDir" }

  // Local tags in this segment that hand-written bindings already own. Comma separated.
  val reserved = options["reserved"]
    ?.split(",")
    ?.mapNotNull { it.trim().toIntOrNull() }
    ?.toSet()
    .orEmpty()

  val components = SurfaceParser().parseFiles(sources)
  val dictionary = buildDictionary(segmentName, segmentId, version, components, reserved)

  // Layer 1's build-time check. A renumbered tag does not fail to render; it renders the wrong
  // widget on a client one dictionary version behind, so this fails the build rather than the
  // screen.
  options["lock"]?.let { lockPath ->
    when (val result = checkAgainstLock(dictionary, File(lockPath))) {
      is LockResult.Violated -> error(
        buildString {
          appendLine("dictionary lock violated; tags are permanent:")
          for (problem in result.problems) appendLine("  - " + problem)
          appendLine("Append to the surface instead of reordering or removing.")
        },
      )
      is LockResult.Updated -> println("dogwood-codegen: dictionary lock updated, added ${result.added}")
      LockResult.Unchanged -> Unit
    }
  }

  guestOut.parentFile.mkdirs()
  hostOut.parentFile.mkdirs()
  dictionaryOut.parentFile.mkdirs()
  guestOut.writeText(emitGuestStubs(guestPackage, dictionary, components))
  hostOut.writeText(emitHostBindings(hostPackage, implementationPackage, dictionary, components))
  dictionaryOut.writeText(dictionary.encode())

  val rejected = components.filterNot { it.isBindable }
  println("dogwood-codegen: ${components.size} composables, ${components.size - rejected.size} bound, ${rejected.size} rejected")
  for (component in rejected) {
    for (parameter in component.parameters.filter { it.kind == ParameterKind.UNSUPPORTED }) {
      println("  rejected ${component.name}.${parameter.name}: ${parameter.rejection}")
    }
  }
}
