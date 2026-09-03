/*
 * Project Dogwood -- the network policy on iOS, tested with the same teeth as the JVM one.
 *
 * The JVM policy has had contract tests since the service surface landed. The iOS transliteration
 * shipped with none, and the review that followed found the gap those tests would have caught on
 * the first run: NSURL parses any scheme and NSURLSession serves `file:` and `data:` natively, so
 * a rule that only waived "must be https" for cleartext hosts passed
 * `file://localhost/<the app container>` -- the app's own sandbox, readable through the "network"
 * policy and exfiltrable to any allowed host. A security boundary ported without its tests is a
 * security boundary ported without its guarantees.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.Foundation.NSURL

private fun url(text: String): NSURL = NSURL.URLWithString(text)!!

class NetworkPolicyIosTest {

  private val allow = allowUrlHosts("api.example.com", "localhost", allowCleartextHosts = setOf("localhost"))

  @Test
  fun httpsToANamedHostIsAllowed() {
    assertTrue(allow(url("https://api.example.com/feed")))
    assertTrue(allow(url("https://localhost:8080/feed")))
  }

  @Test
  fun anUnnamedHostIsRefusedWhateverTheScheme() {
    assertFalse(allow(url("https://evil.example/x")))
    assertFalse(allow(url("http://evil.example/x")))
  }

  @Test
  fun cleartextIsPerHostAndMeansHttpOnly() {
    assertTrue(allow(url("http://localhost:8080/feed")), "the opt-in names this host")
    assertFalse(allow(url("http://api.example.com/feed")), "named for https does not mean named for http")
  }

  @Test
  fun theFileSchemeNeverPassesEvenForACleartextHost() {
    // The finding this file exists for. NSURL gives file: URLs a host, NSURLSession serves them
    // from the local filesystem, and the pre-review rule read "not https, but host is waived" as
    // permission. The app's own container -- caches, cookies, a future state store -- was readable
    // through the network policy and exfiltrable to any allowed host.
    assertFalse(allow(url("file://localhost/var/mobile/anything")))
    assertFalse(allow(url("file:///etc/hosts")))
  }

  @Test
  fun otherNonHttpSchemesNeverPass() {
    assertFalse(allow(url("ftp://localhost/x")))
    assertFalse(allow(url("data://localhost/x")))
  }

  @Test
  fun schemeMatchingIsCaseInsensitiveAndFailsClosed() {
    assertTrue(allow(url("HTTPS://api.example.com/x")), "NSURL preserves case; the policy must not care")
    assertFalse(allow(url("FILE://localhost/x")))
  }
}
