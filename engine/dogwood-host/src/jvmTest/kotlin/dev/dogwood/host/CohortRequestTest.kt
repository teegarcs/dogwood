/*
 * Project Dogwood -- the client's half of staged rollout, on the wire.
 *
 * `InstallCohort` has given every installation a stable bucket 0-99 since ADR-049, and until now
 * **nothing consumed it**: the number was computed, persisted, and sent nowhere. A capability whose
 * only evidence is a number in local storage is a capability nobody can act on, and the reference
 * server's cohort routing had no client on the other end of it.
 *
 * Two kinds of claim here, and the split is deliberate.
 *
 *   * [withCohort] is pure and its rules are asserted directly -- what happens to an address that
 *     already has a query, one that already names a cohort, and a host that never opted in.
 *   * `theRequestActuallyCarriesTheBucket` asserts **the address the client asked for**, recorded
 *     by the Hypertext Transfer Protocol (HTTP) client the loader was handed. That is the
 *     observable consequence; the rest is the property that ought to imply it, and `AGENTS.md`
 *     section 1.5 exists because those are two different things. A delivery that built the right
 *     string and passed the original to the loader would satisfy every other test on this page.
 */
package dev.dogwood.host

import app.cash.zipline.loader.ZiplineCache
import app.cash.zipline.loader.ZiplineHttpClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okio.ByteString
import okio.FileSystem
import java.util.concurrent.Executors

private class CohortMemoryStore(private var text: String? = null) : ReleaseStore {
  override fun read(): String? = text
  override fun write(text: String) {
    this.text = text
  }
}

/** Records what was asked for, then fails: nothing here is about what a server would answer. */
private class RecordingHttpClient : ZiplineHttpClient() {
  val requested = mutableListOf<String>()

  override suspend fun download(
    url: String,
    requestHeaders: List<Pair<String, String>>,
  ): ByteString {
    requested += url
    throw okio.IOException("no server in this test")
  }
}

class CohortRequestTest {

  private fun delivery(
    httpClient: ZiplineHttpClient,
    installCohort: InstallCohort?,
  ): DogwoodDelivery {
    val directory = File.createTempFile("dogwood-cohort", "").also {
      it.delete()
      it.mkdirs()
      it.deleteOnExit()
    }
    return DogwoodDelivery(
      dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
      trustedPublicKeys = dev.dogwood.protocol.DogwoodTrust.DEVELOPMENT_KEYS,
      cache = ZiplineCache(
        fileSystem = FileSystem.SYSTEM,
        directory = cachePath(directory.absolutePath),
        maxSizeInBytes = 4L * 1024 * 1024,
      ),
      httpClient = httpClient,
      installCohort = installCohort,
    )
  }

  @Test
  fun theRequestActuallyCarriesTheBucket() {
    val http = RecordingHttpClient()
    val cohort = InstallCohort(CohortMemoryStore()) { 42 }
    val delivery = delivery(http, cohort)

    // The load fails -- there is no server -- and that is fine: the claim is about the address the
    // client asked for, which is decided before any answer arrives.
    runCatching {
      runBlocking { delivery.load("dogwood-test", "https://example.invalid/manifest.zipline.json") }
    }

    assertTrue(http.requested.isNotEmpty(), "the client never issued a request")
    assertEquals(
      "https://example.invalid/manifest.zipline.json?cohort=42",
      http.requested.first(),
      "the manifest request did not carry the installation's bucket",
    )
  }

  @Test
  fun aHostThatDidNotOptInSendsExactlyWhatItSentBefore() {
    // The compatibility claim, and the reason the parameter defaults to null: every host written
    // before this existed must issue a byte-identical request, or adding staged rollout would
    // change what a static server sees for every deployment that never wanted it.
    val http = RecordingHttpClient()
    val delivery = delivery(http, installCohort = null)

    runCatching {
      runBlocking { delivery.load("dogwood-test", "https://example.invalid/manifest.zipline.json") }
    }

    assertEquals("https://example.invalid/manifest.zipline.json", http.requested.first())
  }

  @Test
  fun anAddressThatAlreadyHasAQueryKeepsIt() {
    // A manifest served from `?release=canary` is an ordinary deployment. Replacing the query
    // would change WHICH payload was fetched rather than which cohort asked for it.
    assertEquals(
      "https://example.invalid/manifest.json?release=canary&cohort=7",
      withCohort("https://example.invalid/manifest.json?release=canary", 7),
    )
  }

  @Test
  fun anAddressThatAlreadyNamesACohortIsLeftAlone() {
    // Two `cohort=` parameters is a request whose meaning is the server's guess. A caller that put
    // one there meant it.
    assertEquals(
      "https://example.invalid/manifest.json?cohort=3",
      withCohort("https://example.invalid/manifest.json?cohort=3", 7),
    )
  }

  @Test
  fun aNullBucketAppendsNothing() {
    assertEquals(
      "https://example.invalid/manifest.json",
      withCohort("https://example.invalid/manifest.json", null),
    )
  }
}
