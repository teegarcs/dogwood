/*
 * Project Dogwood -- the live-state types a generated library tier knows how to mirror.
 *
 * [ADR-043](../../../../../../../../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md)
 * settled what a holder is: the host owns the real object, the guest holds a mirror of it, and what
 * crosses is a handful of target properties plus one report event. On a surface an author controls,
 * a holder is declared with `@Holder` and the generator checks the type against a registered shape.
 *
 * A library cannot be annotated. So a library tier recognises its holders **by type name**, which
 * is the same concession its affordance rule already makes (ADR-072: nobody can put `@Affordance`
 * on `androidx.compose.material3.Switch`, so `enabled`, `checked`, `selected` and `readOnly` are
 * recognised by name). This file is that table.
 *
 * **Adding an entry here is half the work.** Each one names a host-side mirror function that must
 * exist and must be hand-written, because what a holder *does* -- move a pager, open a drawer,
 * answer with the time a user picked -- is the part that requires taste and is not the part that
 * grows without bound. An entry whose mirror does not exist fails the host compile with the name
 * in the message, which is the right failure: loud, immediate, and impossible to ship.
 *
 * **What is deliberately absent.** `SubcomposeLayoutState` is a layout primitive with no product
 * meaning across a boundary -- a guest cannot subcompose, because subcomposition is measurement and
 * measurement happens inside a frame. It stays unbound and the coverage report says so.
 */
package dev.dogwood.codegen.v2

import dev.dogwood.codegen.HolderArgument
import dev.dogwood.codegen.HolderProperty
import dev.dogwood.codegen.HolderReport
import dev.dogwood.codegen.HolderShape

internal object LibraryHolders {

  /**
   * Every shape, by the library type it mirrors.
   *
   * The suffixes and field names match the guest-side holder classes in `dogwood-compose`; the
   * `mirror` names match the host-side functions in `dogwood-host`. Three names in three places is
   * the cost of the generator not resolving types, and the host compiler is what keeps them
   * honest.
   */
  val SHAPES: Map<String, HolderShape> = listOf(
    /*
     * Time selection: a request that answers with what the user chose.
     *
     * **The same shape the design system's own time picker uses** (ADR-053), field for field, and
     * that is the decision rather than an economy. A payload should have one idea of "a time
     * picker's state" whether the widget it hands it to came from a product's design system or
     * from Material 3 — so this reuses `dev.dogwood.compose.TimePickerState`, the guest class that
     * already exists, and only the host mirror is new. Writing a second guest class with the same
     * name in the same package is how this was discovered: it shadowed the first and the design
     * system's own stubs stopped compiling.
     *
     * The value crosses as `HH:MM` text for the reason the date picker crosses ISO-8601: a
     * client's locale must not be baked into the wire.
     */
    HolderShape(
      type = "TimePickerState",
      mirror = "rememberLibraryTimePickerState",
      properties = listOf(
        HolderProperty(suffix = "Visible", type = "Boolean", field = "requested", absent = "false"),
        HolderProperty(suffix = "Sequence", type = "Int", field = "requestSequence", absent = "0"),
        HolderProperty(suffix = "Initial", type = "String", field = "initialTime", absent = "\"\""),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("sequence", "Int", "0"),
          HolderArgument("time", "String", "\"\""),
        ),
      ),
    ),
  ).associateBy { it.type }
}
