/*
 * Project Dogwood -- the generator fails the build on a component it cannot bind.
 *
 * It did not. A rejected component was a line on standard output and an exit code of zero, and
 * the consequence was the one a silent failure always has here: Umbra's `UmbraStepper` sat
 * unbindable for as long as it existed, dropped from both ends of the boundary, while the build
 * stayed green and ADR-006 said the build would fail. These drive the real entry point -- the
 * same `main` the Gradle plugin invokes -- rather than the emitters, because the emitters were
 * never the problem; the exit code was.
 */
package dev.dogwood.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefusalTest {

  private fun generate(surface: String, vararg extra: String): File {
    val root = File.createTempFile("dogwood-refusal", "").also { it.delete(); it.mkdirs() }
    File(root, "surface").mkdirs()
    File(root, "surface/Surface.kt").writeText(surface)
    main(
      arrayOf(
        "--source", File(root, "surface").absolutePath,
        "--segment", "acme",
        "--segment-id", "7",
        "--version", "1",
        "--guest-package", "dev.acme.guest",
        "--host-package", "dev.acme.host",
        "--impl-package", "dev.acme.host",
        "--guest-out", File(root, "out/Stubs.kt").absolutePath,
        "--host-out", File(root, "out/Bindings.kt").absolutePath,
        "--dictionary-out", File(root, "out/acme.json").absolutePath,
        *extra,
      ),
    )
    return root
  }

  @Test
  fun anUnbindableComponentFailsTheBuildNamingTheParameterAndTheAllowedTypes() {
    val failure = assertFailsWith<IllegalStateException> {
      generate("@Composable fun Segmented(options: List<String>, onSelect: (Int) -> Unit) {}")
    }
    val message = failure.message.orEmpty()
    assertTrue("Segmented.options" in message, message)
    assertTrue("List<String>" in message, message)
    assertTrue("String, Int, Long, Float, Double, Boolean" in message, message)
    assertTrue("--allow-unbindable" in message, message)
  }

  @Test
  fun nothingIsWrittenWhenTheBuildIsRefused() {
    // A refusal that left half-generated files behind would let a stale binding from the previous
    // run compile against the new surface, which is a subtler failure than the one being refused.
    val root = File.createTempFile("dogwood-refusal", "").also { it.delete(); it.mkdirs() }
    File(root, "surface").mkdirs()
    File(root, "surface/Surface.kt").writeText("@Composable fun Segmented(options: List<String>) {}")
    runCatching {
      main(
        arrayOf(
          "--source", File(root, "surface").absolutePath,
          "--segment", "acme", "--segment-id", "7", "--version", "1",
          "--guest-package", "g", "--host-package", "h", "--impl-package", "h",
          "--guest-out", File(root, "out/Stubs.kt").absolutePath,
          "--host-out", File(root, "out/Bindings.kt").absolutePath,
          "--dictionary-out", File(root, "out/acme.json").absolutePath,
        ),
      )
    }
    assertFalse(File(root, "out/Stubs.kt").exists())
    assertFalse(File(root, "out/Bindings.kt").exists())
  }

  @Test
  fun theAuditSwitchRestoresTheOldBehaviourForASurfaceInProgress() {
    val root = generate(
      """
      @Composable fun Fine(label: String) {}
      @Composable fun Segmented(options: List<String>) {}
      """,
      "--allow-unbindable", "true",
    )
    val stubs = File(root, "out/Stubs.kt").readText()
    assertTrue("fun Fine(" in stubs)
    // Left out of both ends, and recorded in the dictionary as before.
    assertFalse("fun Segmented(" in stubs)
    assertTrue("\"rejected\"" in File(root, "out/acme.json").readText())
  }

  @Test
  fun aBindableSurfaceGeneratesAsBefore() {
    val root = generate("@Composable fun Fine(label: String, onClick: () -> Unit) {}")
    assertTrue(File(root, "out/Stubs.kt").exists())
    assertTrue(File(root, "out/Bindings.kt").exists())
  }
}
