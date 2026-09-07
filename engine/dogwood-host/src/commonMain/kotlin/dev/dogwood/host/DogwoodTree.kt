/*
 * Project Dogwood -- rendering a host tree, with no transport in sight.
 *
 * This lived in the Zipline source set while its own documentation said it renders "a host tree,
 * with no Zipline instance in sight" and named the Web profile as a caller. Nothing in it touches
 * Zipline; what kept it there was that it had been written next to the thing that does.
 *
 * The cost was invisible until something looked: a host binding could not be exercised by a test
 * that runs on every target, because the entry point to render one was not on every target. That
 * is `plans/conformance.md` Part 7's gap -- claims asserted on one Java Virtual Machine and made
 * for four clients -- with a cause rather than a caveat.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a host tree, with no Zipline instance in sight.
 *
 * Split out from [DogwoodSurface] because the tree is the renderable thing and the experience is
 * only where this one came from. A host that gets its tree some other way -- a test, a preview, or
 * the Web profile, where the guest loads into the browser's own engine rather than through
 * `ZiplineLoader` -- renders it here without pretending to have a QuickJS instance.
 *
 * @param evaluatorKey what the expression cache's lifetime is tied to. The cache holds host
 *   objects built from one guest's recipes, so a new guest must not inherit the old one's.
 */
@Composable
fun DogwoodTree(
  tree: HostTree,
  events: EventSink,
  modifier: Modifier = Modifier,
  evaluatorKey: Any? = tree,
  skew: SkewReport = androidx.compose.runtime.remember(evaluatorKey) { SkewReport() },
) {
  val evaluator = androidx.compose.runtime.remember(evaluatorKey, skew) { ExpressionEvaluator(skew) }
  androidx.compose.runtime.CompositionLocalProvider(
    LocalExpressionEvaluator provides evaluator,
    LocalSkewReport provides skew,
    // The same key the expression cache uses, for the same reason: it identifies one guest. A
    // mirror that reports on change needs to know when the thing it reports *to* was replaced.
    LocalGuestGeneration provides (evaluatorKey ?: tree),
  ) {
    androidx.compose.foundation.layout.Column(modifier) {
      RenderChildren(tree.root, slot = 1, scope = LayoutScope(column = this), events = events)
    }
  }
}
