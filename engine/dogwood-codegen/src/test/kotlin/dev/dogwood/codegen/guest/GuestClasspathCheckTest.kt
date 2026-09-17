/*
 * Project Dogwood -- the classpath check, run as a build rather than argued about.
 *
 * The thing under test is what Gradle's *resolution* produces. A unit test over a hand-built graph
 * would assert this author's belief about resolution, and a belief about resolution is exactly the
 * kind of proxy that is a second thing able to be wrong: whether a `compile`-scoped Project Object
 * Model (POM) dependency lands on `runtimeClasspath`, whether a transitive module appears in the
 * result at all, whether the task is wired into `check`. So the fixture in
 * `src/test/resources/guest-classpath-fixture` is a real build, run by Gradle TestKit with the real
 * plugin on its classpath, and these tests read its real output.
 *
 * ## What was watched
 *
 * The failing cases were watched before the check was believed, in both directions:
 *
 * 1. With `flattenResolution` returning an empty list -- the shape a check that inspects nothing
 *    would have -- `aTransitivelyReachedArtifactFailsTheBuild` passed the fixture build and this
 *    test went red on the missing failure. That is the gate failing without the fix.
 * 2. With the banned artifact removed from the fixture's dependencies, the same build succeeds.
 *
 * The fixture can be run by hand, which is how that was done:
 *
 * ```
 * ./gradlew :dogwood-codegen:test --tests '*GuestClasspathCheckTest' --max-workers=2
 * ```
 */
package dev.dogwood.codegen.guest

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class GuestClasspathCheckTest {

  /**
   * A private copy of the fixture, because a Gradle build writes into its own directory.
   *
   * The fixture is read from the test runtime classpath rather than by walking up to `src`, so it
   * works the same whether the test is run from Gradle or from an editor.
   */
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  private fun fixture(): File {
    val marker = javaClass.classLoader.getResource("guest-classpath-fixture/settings.gradle.kts")
      ?: error("the fixture build is not on the test classpath")
    val source = Path.of(marker.toURI()).parent
    val destination = Files.createTempDirectory("dogwood-guest-fixture")
    for (path in source.walk()) {
      val target = destination.resolve(path.relativeTo(source).toString())
      if (path.isDirectory()) target.createDirectories() else {
        target.parent.createDirectories()
        path.copyTo(target, overwrite = true)
      }
    }
    return destination.toFile()
  }

  private fun runner(projectDir: File, vararg arguments: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withPluginClasspath()
      // One worker: this build resolves two POM files and compiles nothing, and it is running
      // inside another build that needs the memory more.
      .withArguments(*arguments, "--max-workers=1", "--stacktrace")

  @Test
  fun aCleanGuestClasspathPasses() {
    val result = runner(fixture(), "dogwoodGuestClasspathCheck").build()
    assertTrue(
      result.output.contains("nothing forbidden"),
      "the check should say what it inspected:\n${result.output}",
    )
  }

  @Test
  fun aDirectlyDeclaredBannedArtifactFailsTheBuild() {
    val result = runner(
      fixture(),
      "dogwoodGuestClasspathCheck",
      "-PfixtureDependency=androidx.compose.animation:animation-core:1.9.0",
    ).buildAndFail()

    assertTrue("androidx.compose.animation:animation-core:1.9.0" in result.output, result.output)
    assertTrue("declared directly by this module" in result.output, result.output)
    assertTrue("ADR-020" in result.output, result.output)
  }

  @Test
  fun aTransitivelyReachedArtifactFailsTheBuild() {
    // The case the source scan cannot see, and the whole reason this check exists: the fixture's
    // own source names nothing forbidden, and its build file names nothing forbidden either. The
    // banned artifact arrives behind `com.example:helper`.
    val result = runner(
      fixture(),
      "dogwoodGuestClasspathCheck",
      "-PfixtureDependency=com.example:helper:1.0",
    ).buildAndFail()

    assertTrue(
      "com.example:helper:1.0 -> androidx.compose.animation:animation-core:1.9.0" in result.output,
      "the report must name the route, not just the artifact:\n${result.output}",
    )
    assertTrue(
      "exclude(group = \"androidx.compose.animation\", module = \"animation-core\")" in result.output,
      result.output,
    )
  }

  @Test
  fun theCheckRunsAsPartOfCheck() {
    // A rule enforced only when somebody remembers to ask is not enforced. `check` is where a
    // person expects to meet it, and where continuous integration already looks.
    val result = runner(
      fixture(),
      "check",
      "-PfixtureDependency=androidx.compose.animation:animation-core:1.9.0",
    ).buildAndFail()

    assertTrue(
      result.task(":dogwoodGuestClasspathCheck")?.outcome == TaskOutcome.FAILED,
      "check did not reach the classpath check:\n${result.output}",
    )
  }

  @Test
  fun theCheckNeverGoesUpToDate() {
    // Resolution can change without a file in the module changing -- a transitive dependency added
    // upstream, a version alignment moving. A task that went up to date on an unchanged source
    // tree would stop looking on exactly the day it mattered.
    val projectDir = fixture()
    runner(projectDir, "dogwoodGuestClasspathCheck").build()
    val second = runner(projectDir, "dogwoodGuestClasspathCheck").build()
    assertTrue(
      second.task(":dogwoodGuestClasspathCheck")?.outcome == TaskOutcome.SUCCESS,
      "second run: ${second.task(":dogwoodGuestClasspathCheck")?.outcome}",
    )
  }
}
