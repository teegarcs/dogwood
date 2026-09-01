/*
 * Project Dogwood -- host-side modifier reconstruction.
 *
 * Compose's own `Modifier.Element` implementations are `internal`, so a guest cannot serialize
 * one. Dogwood therefore defines its own tagged type and reconstructs a real Compose modifier
 * here. This is the Phase 2 subsystem in embryo: it carries only modifiers whose arguments are
 * primitives and value classes, which is exactly the scope roadmap.md gives Phase 2. Modifiers
 * with expression arguments -- `clip(RoundedCornerShape(8.dp))`, `background(brush)` -- need
 * the deferred-expression grammar and are absent by design rather than by oversight.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val PADDING = 1
private const val FILL_MAX_WIDTH = 2
private const val WEIGHT = 3
private const val SIZE = 4
private const val ALPHA = 5
private const val WIDTH = 6
private const val HEIGHT = 7
private const val ALIGN = 8
private const val CLIP = 9
private const val BACKGROUND = 10

/**
 * Rebuilds a node's modifier chain, in order.
 *
 * Order is load-bearing: `padding(8).size(48)` and `size(48).padding(8)` produce different
 * layouts, so the chain is replayed exactly as the guest recorded it.
 *
 * `weight` is a scope function on `RowScope` and `ColumnScope` rather than a free modifier,
 * which is why [LayoutScope] has to be threaded down to every child. A weight applied outside
 * a row or column has no meaning; here it is skipped, and Phase 2 is required to turn that into
 * a build error at the call site rather than a silent drop at runtime.
 */
@Composable
fun WidgetView.composeModifier(scope: LayoutScope): Modifier {
  val evaluator = LocalExpressionEvaluator.current
  var modifier: Modifier = Modifier
  for (element in modifiers) {
    // Expression arguments are arrays, not primitives, so the primitive view is read lazily.
    val value by lazy(LazyThreadSafetyMode.NONE) { element.v.jsonPrimitive }
    modifier = when (element.t.local) {
      PADDING -> modifier.padding((value.intOrNull ?: 0).dp)
      FILL_MAX_WIDTH -> modifier.fillMaxWidth(value.floatOrNull ?: 1f)
      WEIGHT -> {
        val weight = value.floatOrNull ?: 1f
        when {
          scope.row != null -> with(scope.row) { modifier.weight(weight) }
          scope.column != null -> with(scope.column) { modifier.weight(weight) }
          // Unreachable from a well-formed guest: the guest's scope types make an out-of-scope
          // weight a compile error. Kept, and kept silent, because a host must tolerate a guest
          // built against a different dictionary rather than crash on one.
          else -> modifier
        }
      }
      ALIGN -> {
        val ordinal = value.intOrNull ?: 0
        when {
          scope.row != null -> with(scope.row) { modifier.align(verticalAlignment(ordinal)) }
          scope.column != null -> with(scope.column) { modifier.align(horizontalAlignment(ordinal)) }
          else -> modifier
        }
      }
      // Deferred expressions: the argument is a recipe, not a value, and the host builds it.
      CLIP -> modifier.clip(evaluator.shape(element.v))
      BACKGROUND -> modifier.background(evaluator.color(element.v))
      SIZE -> modifier.size((value.intOrNull ?: 0).dp)
      WIDTH -> modifier.width((value.intOrNull ?: 0).dp)
      HEIGHT -> modifier.height((value.intOrNull ?: 0).dp)
      ALPHA -> modifier.alpha(value.floatOrNull ?: 1f)
      else -> modifier
    }
  }
  return modifier
}


/** Alignments cross as an ordinal; these are the guest enumerations in declaration order. */
private fun verticalAlignment(ordinal: Int): Alignment.Vertical = when (ordinal) {
  0 -> Alignment.Top
  2 -> Alignment.Bottom
  else -> Alignment.CenterVertically
}

private fun horizontalAlignment(ordinal: Int): Alignment.Horizontal = when (ordinal) {
  0 -> Alignment.Start
  2 -> Alignment.End
  else -> Alignment.CenterHorizontally
}
