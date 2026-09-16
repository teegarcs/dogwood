/*
 * Project Dogwood -- generator v2's command-line entry point.
 *
 * Two commands. `coverage` measures; `generate` emits one tier. They share a parse so the report
 * and the bindings cannot disagree about what was bound: the report is the generation's own
 * accounting, not a second reading of the sources.
 */
package dev.dogwood.codegen.v2

import java.io.File

/** The versions the sources tasks pin; repeated here only so the report can print them. */
val PINNED_VERSIONS: Map<String, String> = mapOf(
  "material3" to "1.9.0",
  "foundation" to "1.10.3",
  "foundation-layout" to "1.10.3",
  "ui" to "1.10.3",
)

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
      out.writeText(report.toMarkdown(PINNED_VERSIONS))
      File(out.parentFile, out.nameWithoutExtension + ".json").writeText(report.toJson())
      println("generator-v2 coverage: ${report.overall.bound} of ${report.overall.composables} bound")
    }
    "generate" -> generateTier(
      sources = File(required("sources")),
      module = required("module"),
      wireName = required("wire-name"),
      segmentName = required("segment"),
      segmentId = required("segment-id").toInt(),
      version = required("version").toInt(),
      guestPackage = required("guest-package"),
      hostPackage = required("host-package"),
      guestOut = File(required("guest-out")),
      hostOut = File(required("host-out")),
      dictionaryOut = File(required("dictionary-out")),
      lock = File(required("lock")),
      exclusions = readExclusions(options["exclusions"]?.let(::File)),
      docsOut = options["docs-out"]?.let(::File),
    )
    else -> error("unknown command '$command'")
  }
}
