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
 * **What is deliberately absent, and why the coverage report is not the authority on that.** A
 * report row's reason is the *first* parameter the classifier refused, not the only one, and reading
 * the head of that chain as the whole story has misled this project before (ADR-074 section 3). Two
 * types on the Material 3 list were measured and left out:
 *
 * - `SubcomposeLayoutState` is a layout primitive with no product meaning across a boundary -- a
 *   guest cannot subcompose, because subcomposition is measurement and measurement happens inside a
 *   frame. It stays unbound and the coverage report says so.
 * - `CarouselState` would unlock nothing. The report blames it for three carousels; registering a
 *   throwaway shape and re-running the measurement moved all three to `content: slot receives Int
 *   the guest cannot read`. Every carousel's content slot is
 *   `@Composable CarouselItemScope.(itemIndex: Int) -> Unit` with no default, so the component is
 *   unbindable whatever happens to its state. A carousel needs the generator to model an indexed
 *   slot -- the gap that keeps lazy layouts hand-written (ADR-011) -- which is not a holder.
 *
 * See [ADR-075](../../../../../../../../adrs/layer-5/ADR-075-a-report-says-who-moved-the-control.md)
 * for what each of the shapes below actually unblocked, measured rather than assumed.
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

    /*
     * Snackbars: a request that answers.
     *
     * **The design system's own snackbar shape, field for field**, and the same guest class --
     * `dev.dogwood.compose.SnackbarHostState`. The reasoning is the time picker's above: a payload
     * should have one idea of "ask for a snackbar and find out what the user did with it" whether
     * the host it is talking to renders the product's snackbar or Material 3's. Only the host
     * mirror is new, because only the host mirror knows which library's queue to put it on.
     *
     * The report is not an observation but a **reply**, carrying the sequence it answers so two
     * requests in flight cannot be confused. `DismissSequence` is a second target -- a guest taking
     * back a snackbar it has already asked for -- and it is last in the list because a holder's
     * properties are tags: this one arrived after the other four and was appended, never inserted.
     */
    HolderShape(
      type = "SnackbarHostState",
      mirror = "rememberLibrarySnackbarHostState",
      properties = listOf(
        HolderProperty(suffix = "Message", type = "String", field = "message", absent = "\"\""),
        HolderProperty(suffix = "ActionLabel", type = "String", field = "actionLabel", absent = "\"\""),
        HolderProperty(suffix = "Sequence", type = "Int", field = "sequence", absent = "0"),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
        HolderProperty(suffix = "DismissSequence", type = "Int", field = "dismissSequence", absent = "0"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("sequence", "Int", "0"),
          HolderArgument("actionPerformed", "Boolean", "false"),
        ),
      ),
    ),

    /*
     * Navigation drawers: a two-stop position mirror.
     *
     * The sheet's shape with a smaller vocabulary. A drawer has a position the **user** can change
     * -- swiped open, flung shut, tapped away on the scrim -- so the guest declares where it should
     * be and the host reports where it is, repeatedly, carrying `byUser` so a guest can implement
     * "they closed it, stop offering it". Position alone cannot say that: a drawer that is shut
     * because this guest shut it says nothing about what the user wants.
     *
     * `open` and `closed` cross as strings rather than a boolean, which looks like an economy
     * refused for nothing until you ask what a host one dictionary version *ahead* would send for
     * a third stop. A string lets this guest read that as closed and say so; a boolean would make
     * it the wrong one of two.
     *
     * **This one is worth measuring before believing.** It does not raise the bound count: the two
     * drawer-sheet overloads it makes bindable have guest signatures identical to the already-bound
     * stateless ones, so the overload dedupe excludes them. What it does is upgrade
     * `ModalNavigationDrawer` and `DismissibleNavigationDrawer`, already bound, from a
     * `drawerState` frozen at the library's default to one a payload can drive.
     */
    HolderShape(
      type = "DrawerState",
      mirror = "rememberLibraryDrawerState",
      properties = listOf(
        HolderProperty(suffix = "Target", type = "String", field = "targetState", absent = "\"closed\""),
        HolderProperty(suffix = "Sequence", type = "Int", field = "targetSequence", absent = "0"),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("state", "String", "\"closed\""),
          HolderArgument("byUser", "Boolean", "false"),
        ),
      ),
    ),

    /*
     * Swipe to dismiss: a target, and a report of **which way** the user went.
     *
     * The drawer's shape with a third value in its vocabulary, and the third value is the whole
     * reason the report carries a name rather than a boolean. A row swiped one way and a row swiped
     * the other mean different things in every inbox ever built -- archive one way, delete the
     * other -- and a guest told only "it is gone" would have to guess which.
     *
     * The target half is not symmetry for its own sake: a guest that has just undone a delete has
     * to put the row back, and nothing about the user's gesture can do that.
     */
    HolderShape(
      type = "SwipeToDismissBoxState",
      mirror = "rememberLibrarySwipeToDismissBoxState",
      properties = listOf(
        HolderProperty(suffix = "Target", type = "String", field = "targetState", absent = "\"settled\""),
        HolderProperty(suffix = "Sequence", type = "Int", field = "targetSequence", absent = "0"),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("state", "String", "\"settled\""),
          HolderArgument("byUser", "Boolean", "false"),
        ),
      ),
    ),

    /*
     * Search bars: a two-stop position mirror, and deliberately not a text holder.
     *
     * The drawer's shape again, over expanded and collapsed. What it does **not** carry is the
     * query text: a search field's text is a versioned round trip with its own protocol
     * (ADR-019's `TextInput`), and a second copy of it riding on this holder would be a field that
     * drops keystrokes under load. This says whether the bar is open. Nothing about what is in it.
     */
    HolderShape(
      type = "SearchBarState",
      mirror = "rememberLibrarySearchBarState",
      properties = listOf(
        HolderProperty(suffix = "Target", type = "String", field = "targetState", absent = "\"collapsed\""),
        HolderProperty(suffix = "Sequence", type = "Int", field = "targetSequence", absent = "0"),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("state", "String", "\"collapsed\""),
          HolderArgument("byUser", "Boolean", "false"),
        ),
      ),
    ),

    /*
     * Sliders: a position mirror over a continuous quantity, and the reason is traffic.
     *
     * Material 3 ships a controlled slider -- `value` plus `onValueChange` -- which is already
     * bound as an ordinary property and an ordinary event. This is its state-driven twin, and the
     * difference that earns it a holder is **who decides when a drag is finished**. A controlled
     * slider sends every intermediate value across the boundary, which over a latent link is a
     * change per frame of a drag; a state-driven one settles and reports.
     *
     * `Steps`, `RangeStart` and `RangeEnd` are construction-time rather than targets, exactly as a
     * sheet's `SkipPartial` is: they change what the *gesture* does, and a guest moving the stops
     * mid-drag would be changing the rules under the user's finger. Material's own `SliderState`
     * agrees -- both are `val` on it, so a change means a new state object either way.
     *
     * The range crosses as two `Float` properties rather than one, because a property is a JSON
     * primitive and a range is not; it is the two numbers it always was, exactly as `FloatRange`
     * crosses for the controlled overload.
     */
    HolderShape(
      type = "SliderState",
      mirror = "rememberLibrarySliderState",
      properties = listOf(
        HolderProperty(suffix = "Value", type = "Float", field = "targetValue", absent = "0f"),
        HolderProperty(suffix = "Sequence", type = "Int", field = "targetSequence", absent = "0"),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
        HolderProperty(suffix = "Steps", type = "Int", field = "steps", absent = "0"),
        HolderProperty(suffix = "RangeStart", type = "Float", field = "rangeStart", absent = "0f"),
        HolderProperty(suffix = "RangeEnd", type = "Float", field = "rangeEnd", absent = "1f"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("value", "Float", "0f"),
          HolderArgument("byUser", "Boolean", "false"),
        ),
      ),
    ),

    /*
     * Range sliders: the slider's shape with two thumbs.
     *
     * Two entries rather than one with a thumb count, for the reason the date and time pickers are
     * two entries: the *library* has two state classes with different members, and a mirror that
     * branched on a count would be one function doing two jobs badly. The cost of the duplication
     * is a table entry; the cost of the flag is a mirror nobody can read.
     *
     * The selection reports as two numbers and moves as one request, which is not an inconsistency
     * -- a user drags one thumb at a time, and a guest sets a selection.
     */
    HolderShape(
      type = "RangeSliderState",
      mirror = "rememberLibraryRangeSliderState",
      properties = listOf(
        HolderProperty(suffix = "Start", type = "Float", field = "targetStart", absent = "0f"),
        HolderProperty(suffix = "End", type = "Float", field = "targetEnd", absent = "1f"),
        HolderProperty(suffix = "Sequence", type = "Int", field = "targetSequence", absent = "0"),
        HolderProperty(suffix = "Watching", type = "Boolean", field = "watching", absent = "false"),
        HolderProperty(suffix = "Steps", type = "Int", field = "steps", absent = "0"),
        HolderProperty(suffix = "RangeStart", type = "Float", field = "rangeStart", absent = "0f"),
        HolderProperty(suffix = "RangeEnd", type = "Float", field = "rangeEnd", absent = "1f"),
      ),
      report = HolderReport(
        method = "report",
        arguments = listOf(
          HolderArgument("start", "Float", "0f"),
          HolderArgument("end", "Float", "1f"),
          HolderArgument("byUser", "Boolean", "false"),
        ),
      ),
    ),
  ).associateBy { it.type }
}
