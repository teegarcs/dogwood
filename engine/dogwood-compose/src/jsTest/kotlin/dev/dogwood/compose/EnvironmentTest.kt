/*
 * Project Dogwood -- the host environment, as tests.
 *
 * roadmap.md Phase 4 puts the host environment first and says everything else assumes it. What
 * everything else actually assumes is narrower than "the guest can read the configuration", and
 * these tests pin the narrow version:
 *
 *   - it arrives before the first composition, not after it;
 *   - a change reaches the composition and costs exactly one batch;
 *   - a change costs only the nodes that READ it, not the screen;
 *   - an unchanged environment costs nothing at all.
 *
 * The last one is what makes it safe for a host to push the environment from composition, which
 * is where it is derived. A host that had to work out for itself whether the environment moved
 * would get it wrong, and the failure would be a screen that quietly stops responding to
 * rotation.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.WidthClass
import dev.dogwood.protocol.language
import dev.dogwood.protocol.widthClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val phone = HostEnvironment(
  density = 3f,
  fontScale = 1f,
  darkMode = false,
  viewportWidthDp = 411,
  viewportHeightDp = 891,
  safeAreaTopDp = 24,
  safeAreaBottomDp = 48,
  locale = "en-US",
)

private val tablet = phone.copy(viewportWidthDp = 1024, viewportHeightDp = 768)

class HostEnvironmentTest {

  @Composable
  private fun WidthLabel() {
    Text("width ${LocalHostEnvironment.current.viewportWidthDp}")
  }

  @Test
  fun theEnvironmentIsPresentForTheFirstComposition() {
    // Not "arrives shortly after". The first batch a host ever receives is produced during
    // `start`, so an environment delivered afterwards would mean every experience renders one
    // frame laid out for a device that does not exist.
    val (host, _) = compose(configuration = phone) { WidthLabel() }
    val batch = host.decoded().single()
    val text = batch.g.filterIsInstance<PropertySet>().single()
    assertEquals("width 411", text.v.toString().trim('"'))
  }

  @Test
  fun aChangeReachesTheCompositionAsOneBatch() {
    val (host, composition) = compose(configuration = phone) { WidthLabel() }
    assertEquals(1, host.batches.size)

    composition.updateConfiguration(tablet)
    composition.frame(0L)

    val batches = host.decoded()
    assertEquals(2, batches.size, "one environment change must produce exactly one batch")
    val change = batches[1].g.single()
    assertTrue(change is PropertySet)
    assertEquals("width 1024", change.v.toString().trim('"'))
  }

  @Test
  fun anUnchangedEnvironmentCostsNothing() {
    val (host, composition) = compose(configuration = phone) { WidthLabel() }
    val batchesBefore = host.batches.size
    val framesBefore = host.frameRequests

    // A copy, not the same instance: the host derives a fresh value on every recomposition, so
    // dedupe has to be structural or it does not fire at all.
    composition.updateConfiguration(phone.copy())
    composition.frame(0L)

    assertEquals(batchesBefore, host.batches.size, "an equal environment must produce no traffic")
    assertEquals(framesBefore, host.frameRequests, "an equal environment must not ask for a frame")
  }

  @Test
  fun aChangeAsksForAFrame() {
    val (host, composition) = compose(configuration = phone) { WidthLabel() }
    val before = host.frameRequests
    composition.updateConfiguration(tablet)
    assertTrue(
      host.frameRequests > before,
      "the guest must ask for a frame, because the host only serves one when asked -- otherwise " +
        "the change sits in the composition and never crosses",
    )
  }

  @Composable
  private fun Screen() {
    // One reader, many non-readers. This is the shape of a real screen: a handful of nodes
    // actually branch on the environment and the rest are indifferent to it.
    Column {
      Text("static one")
      Text("static two")
      WidthLabel()
      Text("static three")
    }
  }

  @Test
  fun onlyTheNodesThatReadTheEnvironmentRecompose() {
    val (host, composition) = compose(configuration = phone) { Screen() }
    composition.updateConfiguration(tablet)
    composition.frame(0L)

    val changes = host.decoded()[1].g
    assertEquals(
      1,
      changes.size,
      "a rotation must cost the nodes that read the environment, not the screen; got " +
        changes.map { it::class.simpleName },
    )
  }

  @Test
  fun safeAreaCrossesInDensityIndependentPixels() {
    // The guest has no density it can trust and no way to convert pixels itself, so the
    // conversion is the host's job and the contract is that it has already happened.
    val (host, _) = compose(configuration = phone) {
      Text("bottom ${LocalHostEnvironment.current.safeAreaBottomDp}")
    }
    val text = host.decoded().single().g.filterIsInstance<PropertySet>().single()
    assertEquals("bottom 48", text.v.toString().trim('"'))
  }
}

/**
 * The derived views live in the protocol module so that a guest laying out for "compact" and a
 * host measuring "compact" mean the same thing. If these breakpoints ever drift apart the
 * disagreement is invisible until somebody reports a layout bug on one device.
 */
class DerivedEnvironmentTest {

  @Test
  fun widthClassesFollowTheMaterialBreakpoints() {
    fun at(width: Int) = HostEnvironment(viewportWidthDp = width).widthClass
    assertEquals(WidthClass.Compact, at(0))
    assertEquals(WidthClass.Compact, at(599))
    assertEquals(WidthClass.Medium, at(600))
    assertEquals(WidthClass.Medium, at(839))
    assertEquals(WidthClass.Expanded, at(840))
    assertEquals(WidthClass.Expanded, at(3840))
  }

  @Test
  fun theLanguageSubtagIsTheHalfBeforeTheRegion() {
    assertEquals("en", HostEnvironment(locale = "en-US").language)
    assertEquals("en", HostEnvironment(locale = "en-GB").language)
    assertEquals("ja", HostEnvironment(locale = "ja-JP").language)
    // A bare language tag is legal and must not lose its only subtag.
    assertEquals("de", HostEnvironment(locale = "de").language)
  }
}
