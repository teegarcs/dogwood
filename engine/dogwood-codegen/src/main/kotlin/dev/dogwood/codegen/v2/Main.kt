/*
 * Project Dogwood -- generator v2's command-line entry point.
 *
 * Two commands. `coverage` measures; `generate` emits one tier. They share a parse so the report
 * and the bindings cannot disagree about what was bound: the report is the generation's own
 * accounting, not a second reading of the sources.
 */
package dev.dogwood.codegen.v2

import java.io.File

fun main(args: Array<String>) {
  val command = args.firstOrNull() ?: error("usage: coverage|generate --option value ...")
  val options = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it[1] }
  fun required(name: String) = options[name] ?: error("missing --$name")

  when (command) {
    "coverage" -> {
      val sources = File(required("sources"))
      val out = File(required("out"))
      val exclusions = readExclusions(options["exclusions"]?.let(::File))
      val parser = LibrarySurfaceParser()
      val surfaces = sources.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.map { dir ->
        dir.name to Classifier.classify(parser.parseModule(dir.name, dir))
      }
      val report = buildCoverage(surfaces, exclusions)
      out.parentFile.mkdirs()
      // The versions the host resolved, written beside the sources by `fetchComposeSources`.
      // Read rather than restated, so a report can never name a version the parse did not read
      // (ADR-073).
      out.writeText(report.toMarkdown(readResolvedVersions(File(required("versions")))))
      File(out.parentFile, out.nameWithoutExtension + ".json").writeText(report.toJson())
      println("generator-v2 coverage: ${report.overall.bound} of ${report.overall.composables} bound")
    }
    "generate" -> generateTier(
      sources = File(required("sources")),
      module = required("module"),
      wireName = required("wire-name"),
      segmentName = required("segment"),
      segmentId = required("segment-id").toInt(),
      // Derived from what the host resolves, never passed in. ADR-073.
      version = tierVersion(readResolvedVersions(File(required("versions"))), required("module")),
      acceptDowngrade = options["accept-downgrade"] == "true",
      guestPackage = required("guest-package"),
      hostPackage = required("host-package"),
      guestOut = File(required("guest-out")),
      hostOut = File(required("host-out")),
      dictionaryOut = File(required("dictionary-out")),
      lock = File(required("lock")),
      exclusions = readExclusions(options["exclusions"]?.let(::File)),
      docsOut = options["docs-out"]?.let(::File),
    )
    /*
     * The cross-check `checkGeneratedTierVersions` runs: does the committed lock describe the
     * library this checkout resolves? Here rather than in the build file so there is one
     * implementation of the encoding, and so it can be tested.
     */
    "check-versions" -> {
      val versions = readResolvedVersions(File(required("versions")))
      val problems = required("tiers").split(",").filter { it.isNotBlank() }.mapNotNull { entry ->
        val module = entry.substringBefore('=')
        val lock = File(entry.substringAfter('='))
        if (!lock.isFile) return@mapNotNull "no lock at $lock for module $module"
        val locked = Regex(""""version"\s*:\s*(\d+)""").find(lock.readText())
          ?.groupValues?.get(1)?.toInt()
          ?: return@mapNotNull "${lock.name} names no version"
        val expected = tierVersion(versions, module)
        if (locked == expected) {
          null
        } else {
          "${lock.name} says version $locked (${decodeLibraryVersion(locked)}) and this host " +
            "resolves $module ${versions[module]}, which is version $expected. Regenerate the " +
            "tier and commit the lock, the exclusions, the coverage report and the reference " +
            "together (docs/upgrading-compose.md)."
        }
      }
      if (problems.isNotEmpty()) {
        error(
          buildString {
            appendLine("a committed tier lock does not describe the library this host resolves:")
            for (problem in problems) appendLine("  - $problem")
          },
        )
      }
      println(
        "generator-v2: every tier lock matches what the host resolves (" +
          versions.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" } + ")",
      )
    }
    else -> error("unknown command '$command'")
  }
}
