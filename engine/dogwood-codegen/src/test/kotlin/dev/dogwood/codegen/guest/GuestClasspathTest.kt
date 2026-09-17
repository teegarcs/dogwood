/*
 * Project Dogwood -- the decision half of the classpath check.
 *
 * These tests are about the report, because the report is the whole value. A check that says
 * "androidx.compose.animation:animation-core is forbidden" and stops there sends an author to look
 * through a build file for a line that is not in it: a banned artifact is almost never something
 * anybody typed. The route it took is the part that tells them where to put the `exclude`.
 *
 * The resolution itself is not tested here -- a hand-built graph would be this author's belief
 * about what Gradle resolves, which is a second thing that can be wrong. `GuestClasspathCheckTest`
 * runs a real build for that.
 */
package dev.dogwood.codegen.guest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestClasspathTest {

  private val animationCore = ResolvedModule(
    group = "androidx.compose.animation",
    name = "animation-core",
    version = "1.9.0",
    path = listOf("androidx.compose.animation:animation-core:1.9.0"),
  )

  @Test
  fun anOrdinaryClasspathPasses() {
    val resolved = listOf(
      ResolvedModule("org.jetbrains.compose.runtime", "runtime", "1.10.3"),
      ResolvedModule("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.10.2"),
    )
    assertEquals(emptyList(), checkGuestClasspath("jsRuntimeClasspath", resolved))
  }

  @Test
  fun theComposeRuntimeIsNotBanned() {
    // It cannot be. `withFrameNanos` lives in it and every guest needs it for `remember` and
    // recomposition, which is why that call stays on the *source* list. A classpath check that
    // tried to cover the frame loop would have to ban the runtime, and would ban every guest.
    val resolved = listOf(ResolvedModule("androidx.compose.runtime", "runtime", "1.9.0"))
    assertEquals(emptyList(), checkGuestClasspath("runtimeClasspath", resolved))
  }

  @Test
  fun aBannedArtifactIsFoundAtAnyVersion() {
    // The objection is to what is in the module, not to which release it is.
    for (version in listOf("1.7.0", "1.9.0", "2.0.0-alpha01")) {
      val found = checkGuestClasspath(
        "jsRuntimeClasspath",
        listOf(animationCore.copy(version = version)),
      )
      assertEquals(1, found.size, version)
      assertTrue("frame-clock" in found.single().artifact.because)
    }
  }

  @Test
  fun aSiblingArtifactIsNotBannedForTheCompanyItKeeps() {
    // `animation-graphics` draws animated vector drawables and has no frame clock of its own. A
    // group-wide pattern would reject it, and a check that cries wolf is one people suppress.
    val resolved = listOf(ResolvedModule("androidx.compose.animation", "animation-graphics", "1.9.0"))
    assertEquals(emptyList(), checkGuestClasspath("runtimeClasspath", resolved))
  }

  @Test
  fun bothCoordinateFamiliesAreCovered() {
    // Compose Multiplatform redistributes the same code under `org.jetbrains.compose.*`, and a
    // Kotlin/JavaScript guest resolves that one. A list with only the androidx coordinates would
    // miss every guest this architecture actually builds.
    val androidx = checkGuestClasspath(
      "runtimeClasspath",
      listOf(ResolvedModule("androidx.compose.animation", "animation-core", "1.9.0")),
    )
    val jetbrains = checkGuestClasspath(
      "jsRuntimeClasspath",
      listOf(ResolvedModule("org.jetbrains.compose.animation", "animation-core", "1.10.3")),
    )
    assertEquals(1, androidx.size)
    assertEquals(1, jetbrains.size)
  }

  @Test
  fun everyBannedArtifactCarriesAReplacement() {
    // The same obligation ADR-050 put on the source list: a rejection with no alternative is a
    // rejection somebody works around.
    for (artifact in BANNED_GUEST_ARTIFACTS) {
      assertTrue(artifact.because.isNotBlank(), artifact.coordinate)
      assertTrue(artifact.instead.isNotBlank(), "${artifact.coordinate} has no replacement")
    }
  }

  @Test
  fun aDirectDependencyIsReportedAsOne() {
    val violation = checkGuestClasspath("runtimeClasspath", listOf(animationCore)).single()
    assertTrue(violation.isDirect)
    assertTrue("declared directly" in violation.toString(), violation.toString())
  }

  @Test
  fun aTransitiveDependencyNamesTheRouteItTook() {
    val transitive = animationCore.copy(
      path = listOf("com.example:helper:1.0", "androidx.compose.animation:animation-core:1.9.0"),
    )
    val violation = checkGuestClasspath("jsRuntimeClasspath", listOf(transitive)).single()
    assertTrue(!violation.isDirect)
    assertTrue(
      "com.example:helper:1.0 -> androidx.compose.animation:animation-core:1.9.0" in violation.toString(),
      violation.toString(),
    )
  }

  @Test
  fun theFailureTellsTheAuthorWhereToPutTheExclude() {
    // The most useful sentence in the report, and the one that is not obvious: a transitive
    // dependency is removed where it enters, which is the *other* library's line in the build file.
    val transitive = animationCore.copy(
      path = listOf("com.example:helper:1.0", "androidx.compose.animation:animation-core:1.9.0"),
    )
    val message = guestClasspathFailure(checkGuestClasspath("jsRuntimeClasspath", listOf(transitive)))
    assertTrue("implementation(\"com.example:helper\")" in message, message)
    assertTrue(
      "exclude(group = \"androidx.compose.animation\", module = \"animation-core\")" in message,
      message,
    )
  }

  @Test
  fun theFailureDoesNotOfferAnExcludeForADirectDependency() {
    // Excluding a dependency from itself is not a fix, and printing it would be advice that does
    // not work. The line to delete is the author's own.
    val message = guestClasspathFailure(checkGuestClasspath("runtimeClasspath", listOf(animationCore)))
    assertTrue("exclude(" !in message, message)
    assertTrue("declared directly" in message, message)
  }
}
