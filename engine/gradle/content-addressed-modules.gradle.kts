/*
 * Project Dogwood -- a module file is named for its own content, and the name is signed.
 *
 * ## The defect this closes
 *
 * A Zipline manifest names each module by a relative address, and for every release this project
 * produces that address is the same string: `slice-guest.zipline`. When two releases are live at
 * once -- a canary staged beside the live one, which `tools/reference-server/cohort-drill.sh`
 * arranges deliberately -- a module request says nothing about which release it belongs to. The
 * server has to guess, and a client pinned to the canary was watched fetching the live release's
 * bytes and refusing the load on the digest its signed manifest named. The signature caught it,
 * which is the right direction, but the outcome is a device that cannot start.
 *
 * ## Why this is a build step and not a publishing step
 *
 * The first reading of the defect put the fix in `publish`. It cannot go there. A module's `url`
 * sits beside its `sha256` **inside the signed region** of the manifest, so a publishing step that
 * renamed modules would invalidate every signature it touched.
 * `SignatureTest.movingTheModuleAddressIsRejected` is that fact, asserted, so the next person to
 * reach for the publishing fix finds out in a second rather than in an outage.
 *
 * Nor can the server route around it. `HttpFetcher.withBaseUrl` **removes** any `unsigned.baseUrl`
 * the server sends and replaces it with the URL the client requested, and
 * `ZiplineHttpClient.download` returns bytes rather than a final URL, so neither a declared base
 * nor a redirect can move where a module resolves. (Both read out of `zipline-loader-jvm-1.27.0`,
 * not assumed.) The address the client asks for is the address the build wrote. So the build writes
 * a better one.
 *
 * ## The address
 *
 * `slice-guest.zipline` becomes `slice-guest-<first 16 hex of its SHA-256>.zipline`. Content
 * addressing rather than a per-release path prefix, for three reasons:
 *
 *  - It is unique **by construction**, with no release version to thread through the build. A
 *    prefix has to be told the release; a digest already knows.
 *  - It makes `Cache-Control: immutable` true rather than aspirational. Two releases sharing an
 *    unchanged module share its address, so a canary that changes one module re-downloads one
 *    module. A release prefix re-downloads all of them.
 *  - A collision becomes impossible rather than warned about: equal names mean equal bytes.
 *
 * ## How it is attached
 *
 * As a `doLast` on the Zipline task itself rather than as a task of its own. A separate task would
 * have to be a finalizer, and a finalizer runs *after* the tasks that merely `dependsOn` the task
 * it finalizes -- so `dogwood-host`'s tests, `slice-desktop` and `slice-ios` could all have read
 * the manifest before it was rewritten. Hooking the producer means every existing `dependsOn`
 * already waits for the right bytes and nothing new has to be wired anywhere.
 *
 * The plugin's own `signingKeys` block is left in place, so a manifest that somehow skipped this
 * step is still signed rather than unsigned. The re-signing here replaces those signatures with
 * ones over the rewritten manifest, using the same keys.
 *
 * ## Configuration
 *
 * The applying build sets `extra["dogwoodSigningKeys"]` to an ordered map of signer name to private
 * key hex -- the same keys and the same order as its `zipline { signingKeys { } }` block, because
 * Zipline verifies against the first key name a client recognises and the rotation drill depends on
 * that order. Optionally `extra["dogwoodZiplineTask"]` names the task when it is not
 * `jsBrowserProductionWebpackZipline`.
 */

/*
 * A script plugin applied with `apply(from = ...)` does not inherit the applying project's plugin
 * classpath, so the Zipline types this needs have to be asked for here even though the build file
 * next door can already see them. The version is READ from the catalog rather than restated: a
 * second copy of a version number is a second thing to forget on an upgrade, and
 * `docs/upgrading-compose.md` already has enough of those.
 */
buildscript {
  val ziplineVersion = Regex("""^zipline = "([^"]+)"""", RegexOption.MULTILINE)
    .find(rootDir.resolve("gradle/libs.versions.toml").readText())
    ?.groupValues?.get(1)
    ?: error("no `zipline = \"...\"` version in gradle/libs.versions.toml")
  // `google()` as well as Maven Central: `zipline-loader` pulls `androidx.annotation`, which
  // only Google's repository serves. The same pair `settings.gradle.kts` declares for modules.
  repositories {
    google()
    mavenCentral()
  }
  dependencies { classpath("app.cash.zipline:zipline-loader:$ziplineVersion") }
}

import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestSigner
import okio.ByteString.Companion.decodeHex

@Suppress("UNCHECKED_CAST")
val dogwoodSigners = project.extra["dogwoodSigningKeys"] as Map<String, String>
require(dogwoodSigners.isNotEmpty()) {
  "content-addressed-modules.gradle.kts needs extra[\"dogwoodSigningKeys\"], and it is empty"
}
val dogwoodZiplineTaskName =
  project.extra.properties["dogwoodZiplineTask"] as? String ?: "jsBrowserProductionWebpackZipline"
val dogwoodManifestDir = project.layout.buildDirectory.dir("zipline/ProductionWebpack")

tasks.matching { it.name == dogwoodZiplineTaskName }.configureEach {
  val outputDir = dogwoodManifestDir
  val signers = dogwoodSigners
  doLast {
    val dir = outputDir.get().asFile
    val manifestFile = File(dir, "manifest.zipline.json")
    if (!manifestFile.isFile) {
      throw GradleException(
        "no manifest at ${manifestFile.absolutePath} after $dogwoodZiplineTaskName; " +
          "the content address cannot be written and the payload must not ship without it",
      )
    }

    val original = manifestFile.readText()
    var rewritten = original
    var renamed = 0

    for ((id, module) in ZiplineManifest.decodeJson(original).modules) {
      val digest = module.sha256.hex().take(16)
      val old = module.url
      // Idempotent. The task may run again over an output directory it already addressed -- and
      // the skew drills swap files in behind Gradle's back and re-run it on purpose.
      if (old.endsWith("-$digest.${old.substringAfterLast('.')}")) continue

      val base = old.substringBeforeLast('.')
      val extension = old.substringAfterLast('.')
      val new = "$base-$digest.$extension"
      val oldFile = File(dir, old)
      if (!oldFile.isFile) {
        throw GradleException("manifest names module $id at $old, and no such file is in $dir")
      }

      /*
       * Earlier addresses of this same module are deleted rather than left behind. They are stale
       * bytes under an `immutable` address, which is the exact promise this change exists to make
       * true, and a build directory that accumulated them would hand `publish` a pool of
       * unreferenced modules that nothing can ever ask for.
       */
      val stale = Regex("^" + Regex.escape(base) + "-[0-9a-f]{16}\\." + Regex.escape(extension) + "$")
      dir.listFiles()?.forEach { file ->
        if (file.name != new && stale.matches(file.name)) file.delete()
      }

      oldFile.copyTo(File(dir, new), overwrite = true)
      oldFile.delete()
      // The address is quoted in the JSON, so the quotes are part of the match: a module named
      // `a.zipline` must not be rewritten by a replacement aimed at `a.zipline.map`.
      rewritten = rewritten.replace("\"$old\"", "\"$new\"")
      renamed++
    }

    if (renamed == 0) return@doLast

    /*
     * Re-signed with the same keys, in the same order. `ManifestSigner.sign` builds the signature
     * set from its signers rather than adding to whatever the manifest carried, so the plugin's
     * signatures over the old addresses are replaced rather than accumulated -- verified by
     * `SignatureTest`, which reads this file's output and checks both key names and both
     * verifications.
     */
    val signer = ManifestSigner.Builder()
      .apply { for ((name, hex) in signers) addEd25519(name, hex.decodeHex()) }
      .build()
    manifestFile.writeText(signer.sign(ZiplineManifest.decodeJson(rewritten)).encodeJson())
    logger.lifecycle("content-addressed $renamed module(s) in ${dir.name} and re-signed the manifest")
  }
}
