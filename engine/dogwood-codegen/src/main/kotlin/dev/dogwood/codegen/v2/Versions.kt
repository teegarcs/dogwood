/*
 * Project Dogwood -- the version a generated tier declares, derived rather than typed.
 *
 * ADR-072 says the generator reads "the exact Compose Multiplatform artifacts the host resolves".
 * Until ADR-073 the implementation did not: the Material 3 coordinate carried a hand-typed
 * `1.9.0`, the tier's version argument a hand-typed `10900`, and the coverage report's header a
 * third copy of the first. Three numbers a person had to move together, guarding against a fourth
 * they could not see -- the version the Compose Multiplatform plugin maps `compose.material3` to,
 * which moves when the plugin moves.
 *
 * So the build resolves the version off the host's own compile classpath, writes it beside the
 * extracted sources, and this file turns it into the integer a payload declares in its signed
 * manifest (ADR-061). Nobody types it, and `checkGeneratedTierVersions` refuses a committed lock
 * that disagrees with it.
 */
package dev.dogwood.codegen.v2

import java.io.File

/**
 * `1.9.0` becomes `10900`; `1.9.1`, `10901`; `2.0.0`, `20000`.
 *
 * Two digits each for minor and patch, which is the shape ADR-072 chose and the shape the
 * committed locks already carry. A component of 100 or more would collide with the one above it
 * -- `1.10.0` and `2.0.0` would both be `20000` -- so it is refused rather than truncated. That
 * day will come for a library that reaches `x.100.0`; it will come as a build failure with this
 * sentence in it, not as two libraries sharing a number.
 *
 * A pre-release is refused for a different reason: `1.10.0-beta01` is republished under the same
 * name, and a payload that declared it would be declaring a version that means two different
 * surfaces on two different days. The pre-flight check ([ADR-061]) compares integers; it cannot
 * express "the beta from Tuesday".
 */
fun encodeLibraryVersion(version: String): Int {
  require(version.isNotBlank()) { "a library version cannot be blank" }
  require(!version.contains('-') && !version.contains('+')) {
    "cannot declare a tier for the pre-release version `$version`: a pre-release is republished " +
      "under its own name, and a payload declares a segment version as an integer a host " +
      "compares. Generate a tier from a released version."
  }
  val parts = version.split('.')
  require(parts.size == 3) {
    "cannot read `$version` as major.minor.patch, which is the only shape a segment version encodes"
  }
  val numbers = parts.map { part ->
    part.toIntOrNull() ?: error("cannot read `$version`: `$part` is not a number")
  }
  val (major, minor, patch) = numbers
  require(numbers.all { it >= 0 }) { "cannot encode the negative version `$version`" }
  require(minor < 100 && patch < 100) {
    "cannot encode `$version`: this encoding gives the minor and patch two digits each, and a " +
      "component of 100 or more would collide with the component above it. Widening the encoding " +
      "raises every tier version at once and is a compatibility event, so it is a decision rather " +
      "than a fallback."
  }
  return major * 10_000 + minor * 100 + patch
}

/** The inverse, for messages: `10900` reads back as `1.9.0`. */
fun decodeLibraryVersion(encoded: Int): String =
  "${encoded / 10_000}.${(encoded / 100) % 100}.${encoded % 100}"

/**
 * The versions `fetchComposeSources` resolved, read back from the file it wrote beside the
 * sources.
 *
 * Deliberately not a serialization dependency: this is four lines of `"module": "version"` written
 * by the same build that reads it, and a parser small enough to read is a parser that cannot
 * disagree with the writer about a corner case neither has met.
 */
fun readResolvedVersions(file: File): Map<String, String> {
  require(file.isFile) {
    "no resolved-version file at $file. It is written by `fetchComposeSources`; a generator run " +
      "that does not depend on that task has no way to know what the host resolves."
  }
  val entries = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(file.readText())
    .associate { it.groupValues[1] to it.groupValues[2] }
  require(entries.isNotEmpty()) { "$file names no versions" }
  return entries
}

/** The version a tier for [module] declares, derived from what the host resolved. */
fun tierVersion(versions: Map<String, String>, module: String): Int {
  val resolved = versions[module]
    ?: error(
      "the host resolves no version for `$module`; the modules the generator reads are the ones " +
        "the host compiles against, so a module missing here is a module no host links",
    )
  return encodeLibraryVersion(resolved)
}
