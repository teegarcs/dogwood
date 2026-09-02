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
import dev.dogwood.protocol.PropertySet
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
      Text("x", modifier = Modifier.alpha(animate(0.5f, Animations.tween(400, "linear"))))
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
      Text("x", modifier = Modifier.alpha(animate(if (visible) 1f else 0f)))
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
      Text("x", modifier = Modifier.alpha(animate(target)))
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
      Text("x$tick".take(1), modifier = Modifier.alpha(animate(1f, onFinished = {})))
    }
    val before = host.chains().size
    tick = 1
    composition.frame(0L)
    assertEquals(before, host.chains().size, "an unchanged chain must not re-cross")
  }

  @Test
  fun aCompletionIsRequestedOnlyWhenTheGuestAsksForOne() {
    val (silent, _) = compose { Text("x", modifier = Modifier.alpha(animate(1f))) }
    assertEquals(false, ((silent.chains().single().e.single().v as JsonArray)[3]).jsonPrimitive.content.toBoolean())

    val (noisy, _) = compose {
      Text("x", modifier = Modifier.alpha(animate(1f, onFinished = {})))
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
        modifier = Modifier
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
        modifier = Modifier.alpha(animate(1f, onFinished = { observed = counter })),
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

// ---------------------------------------------------------------------------

/**
 * Colour and repetition, guest side.
 *
 * Both are the same rule as a declared target — the guest names what it wants, the host runs the
 * frames — so the tests are the same shape: what crosses, and how little of it.
 */
class AnimatedColourAndRepeatTest {

  @Test
  fun anAnimatedColourIsStillAColourAndWrapsTheOneItTargets() {
    // The reason it needs no new type: anything typed `Color` accepts it. Typing `Icon.tint` as a
    // colour rather than a token name is what made that free.
    val (host, _) = compose {
      Text("x", modifier = Modifier.background(Color.token("primary").animate()))
    }
    val recipe = host.chains().single().e.single().v as JsonArray
    assertEquals(14, recipe[0].jsonPrimitive.intOrNull, "the animated-colour factory")
    val inner = recipe[1].jsonArray
    assertEquals(4, inner[0].jsonPrimitive.intOrNull, "wrapping an ordinary colour token")
    assertEquals("primary", inner[1].jsonPrimitive.content)
  }

  @Test
  fun anAnimatedColourReachesAGeneratedComponentsParameter() {
    val (host, _) = compose { Icon(name = "flight", tint = Color.token("primary").animate()) }
    val value = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .last { it.p.value == 4 }.v as JsonArray
    assertEquals(14, value[0].jsonPrimitive.intOrNull)
  }

  @Test
  fun anOscillationCrossesItsRangeNotItsFrames() {
    // The whole animation is one property. A pulsing skeleton costs the same as a static one.
    val (host, composition) = compose {
      Text("x", modifier = Modifier.alpha(oscillate(0.35f, 1f, Animations.tween(200))))
    }
    val before = host.batches.size
    repeat(60) { composition.frame(16_666_666L * (it + 1)) }
    assertEquals(before, host.batches.size, "an oscillation must not tick the boundary")

    val recipe = host.chains().single().e.single().v as JsonArray
    assertEquals(15, recipe[0].jsonPrimitive.intOrNull, "the oscillate factory")
    assertEquals(0.35f, recipe[1].jsonPrimitive.content.toFloat())
    assertEquals(1f, recipe[2].jsonPrimitive.content.toFloat())
    assertEquals(0, recipe[3].jsonPrimitive.intOrNull, "zero iterations means forever")
    assertEquals(true, recipe[4].jsonPrimitive.content.toBoolean(), "reversing by default")
  }

  @Test
  fun anInfiniteOscillationMayNotAskForACompletion() {
    // It would never arrive, and a callback that never fires is worse than a compile error: the
    // guest would be waiting on something that cannot happen.
    val failure = runCatching { oscillate(0f, 1f, onFinished = {}) }.exceptionOrNull()
    assertTrue(failure != null, "an infinite oscillation with a callback must be refused")
    assertTrue(failure!!.message!!.contains("never finishes"), failure.message!!)
  }

  @Test
  fun aFiniteOscillationMayAskForOne() {
    val target = oscillate(0f, 1f, iterations = 3, onFinished = {})
    val recipe = target.toJson(notify = true) as JsonArray
    assertEquals(3, recipe[3].jsonPrimitive.intOrNull)
    assertEquals(true, recipe[6].jsonPrimitive.content.toBoolean())
  }

  @Test
  fun aNegativeIterationCountIsRefused() {
    assertTrue(runCatching { oscillate(0f, 1f, iterations = -1) }.isFailure)
  }

  @Test
  fun eachAnimatedFormHasItsOwnFactory() {
    // A declared target, an oscillation and an animated colour must never be mistaken for one
    // another on a client that implements only some of them.
    val declared = (animate(1f).toJson(notify = false) as JsonArray)[0].jsonPrimitive.intOrNull
    val repeated = (oscillate(0f, 1f).toJson(notify = false) as JsonArray)[0].jsonPrimitive.intOrNull
    assertEquals(12, declared)
    assertEquals(15, repeated)
  }
}

// ---------------------------------------------------------------------------

/** An optional callback, and what happens when the guest stops supplying one. */
class OptionalCallbackTest {

  @Test
  fun anAbsentCallbackRegistersNothing() {
    val (host, composition) = compose {
      Presence(visible = true, onExited = null) { Text("x") }
    }
    val node = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == dev.dogwood.protocol.widgetTag(1, 14) }
      .i
    val before = host.unknownNodes
    composition.sendEvent(Event(i = node, e = EventTag(1), q = composition.lastSentSequence))
    assertTrue(host.unknownNodes > before, "an event with no handler must be reported, not swallowed")
  }

  @Test
  fun withdrawingACallbackClearsTheSlotRatherThanLeavingTheOldOne() {
    // Registering nothing is not the same as registering a no-op: a handler from a previous
    // composition would keep receiving events after the guest stopped asking for them.
    var fired = 0
    var listening by mutableStateOf(true)
    val (host, composition) = compose {
      Presence(
        visible = true,
        onExited = if (listening) ({ fired += 1 }) else null,
      ) { Text("x") }
    }
    val node = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == dev.dogwood.protocol.widgetTag(1, 14) }
      .i

    composition.sendEvent(Event(i = node, e = EventTag(1), q = composition.lastSentSequence))
    assertEquals(1, fired)

    listening = false
    composition.frame(0L)
    composition.sendEvent(Event(i = node, e = EventTag(1), q = composition.lastSentSequence))
    assertEquals(1, fired, "the withdrawn callback must not still be receiving events")
  }
}
