# Plan: Closing the Text-Formatting and Animation Gaps

**Date:** 2026-09-01
**Status:** Vetted, not built. Every risky mechanism below was spiked in a scratch test before
being planned; the spikes were then deleted. One spike **falsified the first design** for
repeating animation, and the corrected design is what is planned (see A3).

---

## Part 0 — Verification of the Claims

Each claim was checked mechanically before planning against it.

| # | Claim | How checked | Result |
|---|---|---|---|
| T-1 | A formatting recipe cannot be passed to a generated component | Compiled `Price(price = Formats.currency(61_200, "USD"))` | ✅ Fails: `actual type is 'DogwoodExpression', but 'String' was expected` |
| T-2 | Recipes reach only the layout-tier `Text` | Grep: every `Formats.*` call site flows into `Text(value: DogwoodExpression)`; every generated text parameter is `String` | ✅ Confirmed |
| T-3 | *(found during vetting)* The generator emits **non-compiling code** for a `DogwoodText`-shaped surface type | Ran the parser+emitter on a scratch surface | ✅ Classified `VALUE`, emitted `JsonPrimitive(it)`, which does not compile |
| T-4 | *(found during vetting)* `DogwoodExpression?` (nullable) misclassifies as `VALUE` | Same scratch run | ✅ The `type == "DogwoodExpression"` check never strips `?`, so the `EXPRESSION` kind is dead code for nullable declarations |
| A-1 | Animated colour is not expressible | Compiled `background(animate(1f))` | ✅ Fails: `actual type is 'DogwoodAnimatedValue', but 'DogwoodExpression' was expected` |
| A-2 | The guest cannot read an in-flight animated value | API inspection: `DogwoodAnimatedValue` is opaque, all fields `internal` | ✅ True **by design; this plan does not change it** |
| A-3 | No enter/exit transitions, no repeating animations | No API exists for either | ✅ Trivially confirmed |
| S-1 | `animateColorAsState` is available to `dogwood-host` common code | Spike test | ✅ Resolves (via `compose.foundation`'s dependency on `androidx.compose.animation`) and animates `Palette.Light.primary → Palette.Dark.primary` through intermediate frames on a controlled clock |
| S-2 | A repeat spec can ride `animateFloatAsState` | Spike test | ❌ **Type-checks but does not work.** `finishedListener` never fired and no motion occurred, because `animateFloatAsState` animates only when its *target changes* — a repeating pulse toward a value you are already at is a no-op. **This falsifies the naive design**; see A3 for the corrected one |
| S-3 | Corrected repeat design: explicit `from → to` via `Animatable.animateTo(target, repeatable(n, …))` | Spike test | ✅ Runs through frames, reaches the target, completes exactly once |
| S-4 | Corrected infinite design: `rememberInfiniteTransition` + `animateFloat(initial, target, infiniteRepeatable(…))` | Spike test | ✅ Oscillates across the full range indefinitely |
| S-5 | Exit completion is detectable for an `onExited` event | Spike test: `MutableTransitionState`, `snapshotFlow { isIdle && !targetState }` | ✅ Fires exactly once, and not mid-exit |

**Summary:** every claim in the conversation held. Two latent generator defects (T-3, T-4) were
found *by* the vetting, and one planned design (repeating animation) was corrected *by* the
vetting. Items T-3/T-4 fold into work item **T1**; the S-2 correction defines **A3**.

---

## Part 1 — Text

### T1. `DogwoodText`: formatting recipes reach generated components

**The problem, restated.** Locale-aware text (money, dates, percentages) crosses as a recipe the
host renders, because the pinned QuickJS ships no ECMA-402 `Intl`. But every generated component
parameter is `String`, so a product's own `Price`, `Badge`, `SectionHeader` cannot receive
correctly formatted money — the sample already had to abandon `Price` and hand-assemble rows.

**The design.** One new guest-visible type, one new parameter kind, one composable host accessor.

**1. The type**, in `dogwood-compose/Expressions.kt`:

```kotlin
/** Text that is either a literal or a recipe the host renders. */
class DogwoodText private constructor(internal val json: JsonElement) {
  companion object {
    operator fun invoke(literal: String): DogwoodText = DogwoodText(JsonPrimitive(literal))
    internal fun recipe(expression: DogwoodExpression): DogwoodText = DogwoodText(expression.toJson())
  }
  // Structural equality via JsonElement, so ComposeNode.set() dedupes correctly.
  override fun equals(other: Any?) = other is DogwoodText && other.json == json
  override fun hashCode() = json.hashCode()
}
```

**2. `Formats.*` return `DogwoodText`** instead of `DogwoodExpression`. Verified contained: every
existing call site flows into `Text(value: DogwoodExpression)`, whose overload becomes
`Text(value: DogwoodText)`. Shape/colour expressions are untouched — they stay `DogwoodExpression`.
So the common case reads exactly as it should:

```kotlin
Price(price = Formats.currency(61_200, "USD"))   // recipe
Price(price = DogwoodText("From $612"))          // literal, explicit form
Price(price = "From $612")                       // literal, via the compatibility overload (below)
```

**3. Wire.** No protocol change at all. A literal crosses as `JsonPrimitive`, a recipe as
`JsonArray` — the same property tag carries either, and the two are unambiguous. This is the same
dual encoding the modifier channel already uses for animated values.

**4. Parser** (`dogwood-codegen/Parser.kt`):
- New `ParameterKind.TEXT` for `type.removeSuffix("?") == "DogwoodText"`.
- **Fix T-4 while here**: the `DogwoodExpression` check also strips `?`.
- `ParsedComponent.values` already includes `EXPRESSION`; add `TEXT`.

**5. Guest emitter** (`Emitter.kt`): for `TEXT` (and the now-reachable nullable `EXPRESSION`),
emit `it.json` / `it.toJson()` instead of `JsonPrimitive(it)` — fixing T-3:

```kotlin
set(price) { recording.recorder.property(id, PropertyTag(1), it.json) }
set(note)  { if (it != null) recording.recorder.property(id, PropertyTag(2), it.json) }
```

**6. Host accessor**, new in `dogwood-host` (composable, unlike the readers in `WidgetView.kt`,
because it reads `LocalExpressionEvaluator` and the format context):

```kotlin
@Composable fun WidgetView.text(tag: Int, default: String = ""): String {
  val raw = property(tag) ?: return default
  return when (raw) {
    is JsonArray -> { val f = formatContext()
      LocalExpressionEvaluator.current.text(raw, f.locale, f.timeZoneId, fallback = default) }
    else -> (raw as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: default
  }
}
@Composable fun WidgetView.textOrNull(tag: Int): String?   // same, absent → null
```

The recipe-resolution mechanics are already proven end to end by the existing test
`aFormattedTextNodeRendersTheRecipeRatherThanARawValue`.

**7. Host emitter**: `TEXT` parameters read through `node.text(tag, …)` / `node.textOrNull(tag)`.
**Implementations stay `String`** — `PriceImpl(price: String, …)` is unchanged, because the
binding resolves before calling. The taste layer never learns any of this happened.

**8. Compatibility overload.** Changing `price: String` to `price: DogwoodText` breaks
`Price(price = "x")` at the guest source level. The generator therefore emits **one** extra
overload per component that has ≥ 1 `TEXT` parameter, with *all* `TEXT` parameters as `String`,
delegating via `DogwoodText(...)`. Mixed literal-and-recipe calls use the explicit form. Bounded:
exactly two overloads per component, never a combinatorial set.

**9. Surface migration**: `Price(price, leadingText, previousPrice, trailingText)`, `Badge(text)`,
`SectionHeader(title, description)`, `Chip(text)`, `TextInput(label, placeholder)` move to
`DogwoodText`. Names and declaration order are unchanged ⇒ **property tags are unchanged** ⇒ the
lock passes without edits. Segment version bumps to 5 (the lock enforces this on any semantic
addition; a type widening is one).

**10. Skew.** A new guest sending a recipe to an old host: the old `node.string()` reader sees a
`JsonArray`, which is not a `JsonPrimitive`, and returns the declared default — the standing
degrade-don't-crash rule, at the cost of a blank-ish field. A guest that must not degrade checks
`LocalDogwoodSegments["dogwood.designsystem"] >= 5` before sending recipes into component
parameters. Documented in the ADR, not enforced.

**Tests** (≈ 10): codegen — `TEXT` classification, nullable-`EXPRESSION` fix, both emitted forms
compile in the round-trip fixture, overload emission, tag stability across the type change;
guest — literal crosses as primitive / recipe as array through a generated stub, unset optional
sends nothing; host — `node.text` resolves both forms, unknown factory degrades to the default and
lands in `SkewReport`; device — the sample's hand-assembled price rows go back to `Price(...)`,
verified in `en-US` and `ja-JP`.

**Files:** `Expressions.kt`, `Stubs.kt` (Text overload), `Parser.kt`, `Surface.kt`, `Emitter.kt`,
new `dogwood-host/Text.kt`, `DesignSystemSurface.kt`, sample screens, codegen build (version 5).
**Estimate:** 1–2 days. **Risk:** low — every mechanism exists; this is plumbing with a lock to
keep it honest.

### T2. Plural rules — as a formatting recipe, not a service

"3 nights" / "1 night" / "3 泊" cannot be built guest-side (`Intl.PluralRules` does not exist in
the sandbox) and string tables deliberately have no plural logic.

**Design:** one more text factory. The guest sends the count **and its own templates** (from its
string table); the host picks the CLDR category for its locale and substitutes the formatted
number:

```kotlin
Formats.plural(count = nights, templates = mapOf("one" to "# night", "other" to "# nights"))
// wire: [13, 3, {"one":"# night","other":"# nights"}]
```

Host resolution: category from the platform (`android.icu.text.PluralRules` on Android,
`com.ibm.icu` or an English-only fallback on desktop — decision recorded in the ADR), template
lookup with fallback to `"other"`, `#` replaced by `formatNumber(count)`. Words stay
payload-owned, exactly like `StringTable`; only the *category selection* is host work, which is
the only part that needs the locale data.

**Estimate:** 1 day, sequenced after T1 (it returns `DogwoodText`, so it lands everywhere T1
reaches). **Deliberately deferred, and why:** caret placement (needs a declared-target design of
the ADR-014 kind), richer mask grammar (a grammar deserves its own record), message interpolation
beyond `#` (scope creep toward ICU MessageFormat — decide against wholesale, per-need instead).

---

## Part 2 — Animation

Three additions, one shared principle: **the guest still only declares; nothing here lets the
guest read a per-frame value.** Claim A-2 stays true by design.

### A1. Animated colour

**Guest** (`Animation.kt`): `animateColor(target: DogwoodExpression, spec, onFinished) :
DogwoodAnimatedColor` — the target is an ordinary colour expression, so **tokens animate**:

```kotlin
.background(animateColor(Colors.token(if (selected) "primaryContainer" else "canvas")))
```

`DogwoodModifier.background(DogwoodAnimatedColor)` overload; wire
`[14, colorExpr, spec, notify]` on the existing `BACKGROUND` element — factory 14
(`ANIMATED_COLOR`), disjoint from `COLOR_*` and `ANIMATED_NUMBER`.

**Host** (`Modifiers.kt`): the `BACKGROUND` branch detects factory 14, resolves the target via
`evaluator.color(targetExpr, palette)`, renders through `animateColorAsState(resolved, spec)` with
the same keyed-per-element, `rememberUpdatedState`-guarded completion plumbing as
`animatedNumber`. Two properties fall out free: a **palette flip mid-flight animates** (the
resolved target changes, Compose retargets), and interruption semantics are inherited exactly as
in ADR-020. Availability and behaviour proven by spike S-1.

**Scope:** `background` only in v1. `Icon.tint` and text colour stay static — they are component
*parameters*, and animating parameters is a different (bigger) mechanism than animating modifier
elements. Say so in the ADR. **Estimate:** ~1 day.

### A2. Enter/exit — a `Presence` container, not applier retention

**The design fork, decided:** animating a node *as it is removed* requires someone to keep it
alive after the guest removes it. Doing that in the applier (retain removed nodes until their exit
finishes) breaks the protocol's core invariant — indices in a batch assume removal is immediate,
so every subsequent `ChildAdd/Move` in the same batch would be wrong. **Rejected.** Instead the
guest keeps the node composed and declares visibility; the exit-completion *event* tells it when
removal is safe. That reuses machinery this phase already built (completion events, MutableTransitionState — spike S-5).

**Surface:** one new component in the design-system segment (generated — it is bindable):

```kotlin
@Composable fun Presence(
  visible: Boolean,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  enter: String? = null,      // "fade", "expandVertically", "slideDown", … combinable: "fade+expandVertically"
  exit: String? = null,
  onExited: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {}
```

**Host impl:** `MutableTransitionState` remembered per node (survives code updates like every
binding `remember`), `AnimatedVisibility(visibleState = …)`, named transition parts parsed with
the same degrade-and-report rule as easings; `LaunchedEffect` + `snapshotFlow { isIdle &&
!targetState }` → send `onExited` (spike S-5: fires exactly once, not mid-exit).

**Guest ergonomic for the list-removal case** (pure guest code, no protocol):

```kotlin
val departed = remember { mutableStateListOf<Id>() }
for (item in items + stillExiting) {
  key(item.id) {
    Presence(visible = item.id !in removedIds, exit = "fade+shrinkVertically",
             onExited = { departed += item.id }) { StayCard(item) }
  }
}
```

Ship the raw component plus this documented pattern in v1; a `rememberPresenceList` helper only if
the pattern proves annoying. **Estimate:** 1–2 days. **Out of scope, stated:** shared-element
transitions, and animating a node the guest has *actually* removed (the guest opting out of
`Presence` gets an instant disappearance, exactly as today).

### A3. Repeating and infinite animation — the corrected design

Spike S-2 falsified the obvious design: a repeat spec on the existing `ANIMATED_NUMBER` channel
type-checks and silently does nothing, because `animateFloatAsState` only moves when its target
changes, and a pulse's target *is* its current value. A repeat therefore needs an **explicit
range**, and it is a different recipe, not a spec variant:

```kotlin
fun oscillate(from: Float, to: Float, spec: DogwoodAnimationSpec = Animations.tween(),
              iterations: Int = 0 /* 0 = infinite */, reverse: Boolean = true,
              onFinished: (() -> Unit)? = null): DogwoodAnimatedValue
// wire: [15, from, to, spec, iterations, reverse, notify]
```

Same seven modifier arguments as ADR-020 (`alpha`, `rotate`, `scale`, `width`, `height`, `size`,
`padding`), so a pulsing skeleton is `.alpha(oscillate(0.35f, 1f))` and a spinner is
`.rotate(oscillate(0f, 360f, Animations.tween(1000, "linear"), reverse = false))`.

**Host**, per spikes S-3/S-4 — two primitives, split on finiteness:
- infinite: `rememberInfiniteTransition` + `animateFloat(from, to, infiniteRepeatable(spec, mode))`;
  no completion ever (the recipe rejects `notify` with `iterations = 0` at the guest API level).
- finite: `remember { Animatable(from) }` + `LaunchedEffect(recipe) { animateTo(to,
  repeatable(n, spec, mode)); send(completion) }` — completes exactly once (S-3); a chain change
  cancels the effect, which is the interruption rule.

**Stopping** is a chain change: replace `oscillate(…)` with a plain number or an
`animate(target)` — ordinary recomposition, one crossing. **Cost note for the ADR:** an infinite
animation keeps the *host's* frame loop awake — same as any native spinner, zero boundary traffic,
but worth a sentence so nobody ships a permanently pulsing badge without meaning to.
**Estimate:** ~1 day.

---

## Part 3 — Sequencing, and the records each step owes

| Order | Item | Why this order | ADR |
|---|---|---|---|
| 1 | **T1** `DogwoodText` (+ T-3/T-4 fixes) | Unblocks real products' components; two latent generator defects ride along | ADR-021 |
| 2 | **A1** animated colour | Smallest animation item; exercises the recipe-in-recipe shape A3 also uses | ADR-022 (jointly with A3) |
| 3 | **A3** oscillate | Corrected design is fully specified; shares ADR-022 with A1 | ADR-022 |
| 4 | **A2** `Presence` | Largest; depends on nothing above but benefits from A1/A3's spec parsing being settled | ADR-023 |
| 5 | **T2** plurals | Returns `DogwoodText`, so it wants T1 landed first | ADR-024 |

Each lands with the full gate this phase has used: unit tests both sides, a device verification
pass (ADR-019's lesson — the harness types differently from a keyboard, and by extension animates
differently from a display), sample-screen usage so the feature is visible, and the spec/roadmap
rows updated. Total estimate: **5–7 working days**.

## Part 4 — What this plan deliberately does not fix

Stated so their absence is a decision, not an oversight:

- **Guest-readable animated values** — the constraint is the architecture. Anything that needs the
  in-flight number in guest logic (parallax from scroll offset, progress-driven text) is either a
  registered component's internal business or does not belong on this boundary.
- **Caret control and richer masks** (ADR-019's deferrals) — unchanged.
- **Shared-element transitions** — needs cross-node choreography the protocol cannot express; a
  design question for after the Web host, if ever.
- **Animating component parameters** (`Icon.tint`, text colour) — different mechanism from
  modifier elements; revisit only if products actually ask.
