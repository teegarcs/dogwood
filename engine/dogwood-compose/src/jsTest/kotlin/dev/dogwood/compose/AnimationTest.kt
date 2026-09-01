/*
 * Project Dogwood -- animation, guest side.
 *
 * The claim being tested is a traffic claim. Layer 4 forbids per-frame state in the guest, so the
 * only acceptable animation protocol is one where **a whole animation costs one crossing** -- the
 * one that declares its target. Everything between there and arrival is host work the guest never
 * sees.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.ModifierSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun RecordingHost.chains(): List<ModifierSet> =
  decoded().flatMap { it.g }.filterIsInstance<ModifierSet>()

class AnimatedModifierTest {

  @Test
  fun anAnimatedArgumentCrossesAsATargetAndASpec() {
    val (host, _) = compose {
      Text("x", modifier = DogwoodModifier.alpha(animate(0.5f, Animations.tween(400, "linear"))))
    }
    val element = host.chains().single().e.single()
    val recipe = element.v as JsonArray
    assertEquals(12, recipe[0].jsonPrimitive.intOrNull, "the animated-number factory")
    assertEquals(0.5f, recipe[1].jsonPrimitive.content.toFloat())
    val spec = recipe[2].jsonArray
    assertEquals(1, spec[0].jsonPrimitive.intOrNull, "a tween")
    assertEquals(400, spec[1].jsonPrimitive.intOrNull)
    assertEquals("linear", spec[2].jsonPrimitive.content)
  }

  @Test
  fun aWholeAnimationCostsOneCrossing() {
    // The point of the whole design. A fade is one property set, whatever its duration -- the
    // frames between are host work. A protocol that ticked the guest would be the thing Layer 4
    // forbids, wearing a different name.
    var visible by mutableStateOf(false)
    val (host, composition) = compose {
      Text("x", modifier = DogwoodModifier.alpha(animate(if (visible) 1f else 0f)))
    }
    val before = host.batches.size

    visible = true
    composition.frame(0L)
    // Sixty more frames, as a real animation would take.
    repeat(60) { composition.frame(16_666_666L * (it + 1)) }

    assertEquals(
      before + 1,
      host.batches.size,
      "one target change must be one batch; the animation itself is entirely host-side",
    )
  }

  @Test
  fun retargetingMidFlightIsOneMoreCrossingNotARestart() {
    var target by mutableStateOf(0f)
    val (host, composition) = compose {
      Text("x", modifier = DogwoodModifier.alpha(animate(target)))
    }
    target = 1f
    composition.frame(0L)
    target = 0.25f
    composition.frame(16L)

    val targets = host.chains().map { (it.e.single().v as JsonArray)[1].jsonPrimitive.content.toFloat() }
    assertEquals(listOf(0f, 1f, 0.25f), targets, "each declared target crosses once")
  }

  @Test
  fun anIdenticalTargetCrossesNothing() {
    // Modifier chains compare structurally, and the completion callback is deliberately outside
    // that comparison -- it is a fresh lambda every recomposition, and including it would make
    // every chain look new on every frame.
    var tick by mutableStateOf(0)
    val (host, composition) = compose {
      // `tick` is read, so the composable recomposes, but the modifier does not change.
      Text("x$tick".take(1), modifier = DogwoodModifier.alpha(animate(1f, onFinished = {})))
    }
    val before = host.chains().size
    tick = 1
    composition.frame(0L)
    assertEquals(before, host.chains().size, "an unchanged chain must not re-cross")
  }

  @Test
  fun aCompletionIsRequestedOnlyWhenTheGuestAsksForOne() {
    val (silent, _) = compose { Text("x", modifier = DogwoodModifier.alpha(animate(1f))) }
    assertEquals(false, ((silent.chains().single().e.single().v as JsonArray)[3]).jsonPrimitive.content.toBoolean())

    val (noisy, _) = compose {
      Text("x", modifier = DogwoodModifier.alpha(animate(1f, onFinished = {})))
    }
    assertEquals(true, ((noisy.chains().single().e.single().v as JsonArray)[3]).jsonPrimitive.content.toBoolean())
  }

  @Test
  fun aCompletionArrivesOnATagDerivedFromThePositionInTheChain() {
    // No allocated identifier and nothing extra on the wire: both sides walk the same ordered
    // chain, so the index is an identifier they already agree on.
    var finished = 0
    val (host, composition) = compose {
      Text(
        "x",
        modifier = DogwoodModifier
          .padding(4)
          .alpha(animate(1f, onFinished = { finished += 1 })),
      )
    }
    val node = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.Text }
      .i

    // Element one is the animated alpha; element zero is the padding.
    composition.sendEvent(
      Event(i = node, e = EventTag(ANIMATION_EVENT_BASE + 1), q = composition.lastSentSequence),
    )
    assertEquals(1, finished)

    // And nothing is registered where there is no animation.
    composition.sendEvent(
      Event(i = node, e = EventTag(ANIMATION_EVENT_BASE + 0), q = composition.lastSentSequence),
    )
    assertEquals(1, finished, "an element with no completion must have no handler")
  }

  @Test
  fun aCompletionCallbackIsReboundEveryRecompositionRatherThanCaptured() {
    // The defect ADR-016 found, in a new place. The chain does not change when the callback's
    // captured state does, so a callback bound only when the chain changed would keep calling into
    // the composition it was born in.
    var counter by mutableStateOf(0)
    var observed = -1
    val (host, composition) = compose {
      Text(
        "x",
        modifier = DogwoodModifier.alpha(animate(1f, onFinished = { observed = counter })),
      )
    }
    val node = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.Text }
      .i

    counter = 7
    composition.frame(0L)
    composition.sendEvent(
      Event(i = node, e = EventTag(ANIMATION_EVENT_BASE + 0), q = composition.lastSentSequence),
    )
    assertEquals(7, observed, "the completion fired into a stale closure")
  }

  @Test
  fun everySpecKindHasItsOwnIdentifier() {
    val kinds = listOf(Animations.tween(), Animations.spring(), Animations.Snap)
      .map { (it.json as JsonArray)[0].jsonPrimitive.intOrNull }
    assertEquals(listOf(1, 2, 3), kinds)
    assertTrue(kinds.toSet().size == kinds.size)
  }
}
