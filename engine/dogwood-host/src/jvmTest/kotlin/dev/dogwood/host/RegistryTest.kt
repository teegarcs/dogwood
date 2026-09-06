/*
 * Project Dogwood -- a product's own components, as tests.
 *
 * `specs/layer-5-host.md` calls this item (c) of bespoke subsystem 9 and says why it is the gap
 * that blocks the others: "without (c) a guest can emit only raw Material 3, which no product team
 * ships." Until there was a second segment, `RenderNode` called one binding by name.
 *
 * The sample proves the whole path on a device — Acme's surface, generator, module, segment 2,
 * guest, wire, registry, Acme's implementations. These pin the parts a device shows expensively or
 * not at all: what the registry refuses, and what happens to a segment nobody registered.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Acme's segment in the sample is 2; these use 7 and 8, so nothing here depends on that. */
private val WIDGET_A = widgetTag(7, 1).value
private val WIDGET_B = widgetTag(8, 1).value

/** A product's binding, of the shape the generator emits. */
private class FakeBinding(
  override val segmentName: String,
  private val tag: Int,
  private val label: String,
  override val segmentVersion: Int = 3,
) : DogwoodSegmentBinding {
  override val tags = setOf(tag)
  override val names = mapOf(tag to label)

  @Composable
  override fun bind(node: WidgetView, scope: LayoutScope, events: EventSink): Boolean {
    if (node.tag.value != tag) return false
    Text(label)
    return true
  }
}

@OptIn(ExperimentalTestApi::class)
class RegistryTest {

  @AfterTest
  fun restore() = DogwoodRegistry.resetForTest()

  private fun treeOf(tag: Int) = HostTree().also {
    it.apply(decodePositional("[1,[[0,1,$tag],[3,0,1,1,0]]]"))
  }

  private fun render(tree: HostTree) = runComposeUiTest {
    setContent {
      Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
    }
    waitForIdle()
  }

  @Test
  fun aRegisteredSegmentRenders() {
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "from a product"))
    val tree = treeOf(WIDGET_A)
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      }
      onNodeWithText("from a product").assertIsDisplayed()
    }
    assertTrue(tree.skew.isEmpty, "a registered segment was reported as skew: ${tree.skew}")
  }

  @Test
  fun anUnregisteredSegmentIsAPlaceholderAndIsReported() {
    // The control for the test above, and the behaviour a client one release behind actually has:
    // a product component whose binding this client does not carry renders as an inert box that
    // keeps its slot, and says so. It does not crash and it does not silently vanish.
    val tree = treeOf(WIDGET_B)
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      }
      assertEquals(0, onAllNodesWithText("from a product").fetchSemanticsNodes().size)
    }
    assertEquals(setOf(WIDGET_B), tree.skew.unknownWidgetTags)
  }

  @Test
  fun twoProductSegmentsCoexist() {
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "from acme"))
    DogwoodRegistry.register(FakeBinding("umbra.widgets", WIDGET_B, "from umbra"))
    val tree = HostTree().also {
      it.apply(
        decodePositional("[1,[[0,1,$WIDGET_A],[3,0,1,1,0],[0,2,$WIDGET_B],[3,0,1,2,1]]]"),
      )
    }
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      }
      onNodeWithText("from acme").assertIsDisplayed()
      onNodeWithText("from umbra").assertIsDisplayed()
    }
  }

  @Test
  fun dogwoodsOwnSegmentStillRendersAlongsideAProductsp() {
    // A product registering its own segment must not displace the engine's. The registry has no
    // notion of replacing, only of adding, and this is what that has to mean on screen.
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "from acme"))
    val button = widgetTag(1, 1).value
    val tree = HostTree().also {
      it.apply(
        decodePositional(
          """[1,[[0,1,$button],[1,1,1,"Pay"],[3,0,1,1,0],[0,2,$WIDGET_A],[3,0,1,2,1]]]""",
        ),
      )
    }
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      }
      onNodeWithText("Pay").assertIsDisplayed()
      onNodeWithText("from acme").assertIsDisplayed()
    }
  }

  @Test
  fun registeringTheSameSegmentTwiceIsRefused() {
    // A host with several entry points registering in each of them is the ordinary way this
    // happens, and double dispatch would be subtle. The message is not.
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "one"))
    val failure = assertFailsWith<IllegalArgumentException> {
      DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_B, "two"))
    }
    assertTrue("already registered" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun twoSegmentsClaimingOneTagAreRefused() {
    // The failure the dictionary lock cannot see. A lock is per surface; two surfaces that each
    // pass their own lock can still collide with each other, and the symptom is not a missing
    // widget — it is whichever registered first rendering the other's.
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "acme"))
    val failure = assertFailsWith<IllegalArgumentException> {
      DogwoodRegistry.register(FakeBinding("umbra.widgets", WIDGET_A, "umbra"))
    }
    assertTrue("already binds" in failure.message.orEmpty(), failure.message.orEmpty())
    assertTrue("--segment-id" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun aProductSegmentsVersionReachesTheGuest() {
    // A guest branches on `LocalSegmentVersions` to decide what it may use. A product's segment has
    // to be in that map or every guest assumes the product's components do not exist.
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "acme", segmentVersion = 4))
    assertEquals(4, DogwoodDictionary.segmentVersions["acme.widgets"])
    // And the engine's own are still there, exactly once each.
    assertEquals(
      1,
      DogwoodDictionary.segmentVersions.keys.count { it == "dogwood.designsystem" },
      "the design system appears more than once: ${DogwoodDictionary.segmentVersions}",
    )
  }

  @Test
  fun aProductComponentNamesItselfInDiagnostics() {
    // Skew telemetry reports names. A product's withheld control reported as `Widget#117440513`
    // is the one row a team must act on and the one row nobody can read.
    DogwoodRegistry.register(FakeBinding("acme.widgets", WIDGET_A, "AcmeAction"))
    assertEquals("AcmeAction", DogwoodDictionary.name(dev.dogwood.protocol.WidgetTag(WIDGET_A)))
    assertTrue(DogwoodDictionary.knows(dev.dogwood.protocol.WidgetTag(WIDGET_A)))
    assertFalse(DogwoodDictionary.knows(dev.dogwood.protocol.WidgetTag(WIDGET_B)))
  }

  @Test
  fun aProductSegmentIdentifierMustNotBeDogwoods() {
    // 0 is the layout tier and 1 is Dogwood's design system. Catching this at the call site is
    // cheaper than catching it at registration, and far cheaper than catching it on screen.
    assertFailsWith<IllegalArgumentException> { productTag(0, 1) }
    assertFailsWith<IllegalArgumentException> { productTag(1, 1) }
    assertEquals(WIDGET_A, productTag(7, 1).value)
  }
}
