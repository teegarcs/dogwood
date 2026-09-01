# Plan: Closing the Text-Formatting and Animation Gaps

**Date:** 2026-09-01
**Status:** Vetted, not built. Revised twice after review: Part 1 was reframed from a text fix
to the **host-resolved values** architecture (the destination-state rule in §1.0), and the guest
API sheds the `Dogwood` prefix (T0) — the brand stays at the integration boundary, not in
everyday screen code. Every risky mechanism below was spiked in a scratch test before
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
| S-6 | A guest-shipped number pattern can render with device-locale symbols | Spike test: `DecimalFormat(pattern, DecimalFormatSymbols(locale))` | ✅ `1.234,6` in `de-DE`, `1,234.6` in `en-US` from one pattern; a malformed pattern throws at construction, where it is caught |
| S-7 | The guest API can use Compose's own names (`Modifier`, `Color`, `LazyListState`…) without clashing | Spike: declared a bare-named class in `dev.dogwood.compose`, compiled a guest file wildcard-importing both it and `androidx.compose.runtime.*`; grepped the runtime artifact for each wanted name | ✅ Compiles; the guest classpath carries compose.runtime **only** — no compose.ui, no foundation — and runtime exports none of the six names wanted |

**Summary:** every claim in the conversation held. Two latent generator defects (T-3, T-4) were
found *by* the vetting, and one planned design (repeating animation) was corrected *by* the
vetting. Items T-3/T-4 fold into work item **T1**; the S-2 correction defines **A3**.

---

## Part 1 — Host-Resolved Values (the destination state)

### 1.0 The principle, named

The destination state is not a text feature. It is one rule, applied everywhere:

> **Any value that depends on the device or its settings crosses the wire as a *recipe*, not a
> result. The host resolves the recipe at the moment it draws, against the environment in force.
> When the environment changes, resolution changes — with zero wire traffic and zero guest
> recomposition.**

This rule already exists in the architecture — it is the deferred-expression grammar of
[ADR-010](../adrs/layer-5/ADR-010-deferred-expression-grammar.md) — and it is already load-bearing
in five places:

| The guest ships | Host input it resolves against | Re-resolves when… | Status |
|---|---|---|---|
| `Colors.token("primary")` | the palette in force | **dark mode flips** | ✅ live — this was ADR-012's proof case, verified on the emulator: the accent bar repainted on the theme switch with no traffic |
| `Text(style = "titleLarge")` | the typography in force | host theme changes | ✅ live |
| `Icon(name = "flight")` | the icon set | client updates its set | ✅ live |
| `Formats.currency(61200, "USD")` | locale + time zone + currency data | **device language changes** | ✅ live, but only reaches the bare `Text` |
| `animate(1f, spec)` → `AnimationTarget` | **the host clock** | every frame, host-side | ✅ live — animation is the same rule, where the environment input is time |

So the answer to "what about light mode and dark mode?" is: **dark mode is the case that proved
the pattern.** A colour never crosses as a literal unless the guest explicitly opts out of
theming; it crosses as a token name, and the evaluator's memo is keyed on the palette's identity
precisely so a theme flip re-resolves everything.

What is *missing* is not the capability. It is that the capability stops at the edge of the
generated surface: the generator types every parameter as a raw Kotlin primitive, so recipes
cannot flow into a product's own components. `Price(price:)` is a `String`. `Icon(tint:)` is —
worse — a `String` that happens to hold a token name, invisible to the type system. The work
below is therefore not a workaround for text; it is **teaching the surface language and the
generator that host-resolved values are first-class types**, so the rule above holds across the
whole component surface instead of at three hand-wired spots.

### T0. Names: the brand leaves the screen code

The `Dogwood` prefix was disambiguation that turned out to be unnecessary where it is most
visible. The guest classpath carries `compose.runtime` **only** — no `compose.ui`, no
`foundation` — so Compose's `Modifier`, `Color` and friends do not exist there to clash with
(spike S-7). The parser already accepts bare `Modifier`. The principle, applied throughout:

> **If Compose has the concept, use Compose's exact name. If the concept is Dogwood's own, give
> it a semantic name. The brand appears only at the integration boundary.**

**Bucket A — Compose's names** (this is the "write ordinary Compose" promise kept literally, and
it materially strengthens ADR-006's dual-compile story, where a compositional component library
compiles against both real Compose and the Dogwood stubs from one source):

| Today | Becomes |
|---|---|
| `DogwoodModifier` | `Modifier` |
| `DogwoodLazyListState` / `rememberDogwoodLazyListState` | `LazyListState` / `rememberLazyListState` |
| `DogwoodTextFieldState` / `rememberDogwoodTextFieldState` | `TextFieldState` / `rememberTextFieldState` |
| `DogwoodAnimationSpec` | `AnimationSpec` |
| planned colour type | `Color` — with `Color(0xFF0770E3)` and `Color.token("primary")`, so a literal reads exactly as Compose |
| `Shapes.roundedCorner(…)` return | `Shape` |

**Bucket B — semantic names for Dogwood-only concepts:**

| Today | Becomes | Why this name |
|---|---|---|
| planned text type | `TextValue` | not `Text` — that call site would be ambiguous with the `Text(...)` composable |
| `DogwoodConfiguration` / `LocalDogwoodConfiguration` | `HostEnvironment` / `LocalHostEnvironment` | it *is* the host's environment; "configuration" says nothing |
| `DogwoodAnimatedValue` | `AnimationTarget` | the architecture's own vocabulary: the guest declares a target |
| `DogwoodExpression` | `internal class Recipe` | disappears from the public API entirely — users see `Shape`, `Color`, `TextValue` |
| `GuestServices` / `LocalDogwoodServices` | `HostServices` / `LocalHostServices` | they are the host's services, seen from the guest |
| `LocalDogwoodLaunch` / `LocalDogwoodSegments` | `LocalLaunchParams` / `LocalSegmentVersions` | say what they hold |

**Deliberately kept:** the host embedding API (`DogwoodSurface`, `DogwoodSession`,
`DogwoodExperience`, `DogwoodDelivery`, `DogwoodServiceHost`) — it lives in the same files as real
Compose, where the prefix is genuine disambiguation and conventional for an embedded framework;
the protocol services (`DogwoodHost`, `DogwoodGuestUi`); and the one-line entry point
`DogwoodGuest`, where saying the framework's name once is right.

**Mechanics:** ~300 references, all in-repo, mechanical rename. Runs **first**, so every new type
in T1–A3 lands under its final name and is never migrated. Specs update; shipped ADRs keep their
historical names, as records of their date. **Estimate:** 1 day, including a full device pass.

### T1. The `HOST_RESOLVED` parameter family: `TextValue` and `Color`

One new parameter kind in the generator, with a family of surface types it handles uniformly.
Text is the first member because it is the one blocking products; colour is the second because it
already exists *untyped* (`Icon.tint: String`) and typing it closes a real hole.

**The types**, in `dogwood-compose`:

```kotlin
/** Text that is either a literal or a recipe the host renders against locale + time zone. */
class TextValue private constructor(internal val json: JsonElement) {
  companion object {
    operator fun invoke(literal: String): TextValue = TextValue(JsonPrimitive(literal))
    internal fun recipe(recipe: Recipe): TextValue = TextValue(recipe.toJson())
  }
  override fun equals(other: Any?) = other is TextValue && other.json == json
  override fun hashCode() = json.hashCode()
}

/** A colour that is always a recipe — a token by preference, a literal ARGB by opt-out. */
class Color private constructor(internal val json: JsonElement) {
  companion object {
    operator fun invoke(argb: Long): Color = Color(recipeArgb(argb))   // Color(0xFF0770E3), as in Compose
    fun token(name: String): Color = Color(recipeToken(name))
  }
  // equality as above
}
```

`Formats.*` return `TextValue` (verified contained: every call site flows into
`Text(value:)`, whose overload changes with it). `Color` has no literal-*string* form at all —
a colour is always environment-dependent, and the type now says so out loud while `Color(0xFF…)`
keeps the Compose-literal spelling for the deliberate opt-out.

**Wire: no protocol change.** One property tag carries `JsonPrimitive` (literal) or `JsonArray`
(recipe) — the same dual encoding the modifier channel already uses. Distinguishable, and the
positional codec passes both through untouched today.

**Parser** (`Parser.kt`): a `HOST_RESOLVED` kind matched on the type name with `?` stripped —
which also fixes the two defects vetting found (T-3: a host-resolved surface type currently emits
non-compiling code; T-4: nullable recipe types misclassify because `?` is never stripped).

**Guest emitter**: `set(x) { recording.recorder.property(id, PropertyTag(t), it.json) }`, with the
absence-as-sentinel guard for nullables, exactly as every other optional.

**Host accessors**, composable because they read the evaluator and format context:

```kotlin
@Composable fun WidgetView.text(tag: Int, default: String = ""): String        // primitive → content; array → evaluator.text(...)
@Composable fun WidgetView.textOrNull(tag: Int): String?
@Composable fun WidgetView.color(tag: Int, default: Color): Color               // array → evaluator.color(recipe, palette)
@Composable fun WidgetView.colorOrNull(tag: Int): Color?
```

The mechanics behind both are already proven end to end — `evaluator.text` by the existing
recipe-rendering test, `evaluator.color` by the dark-mode pass.

**Host emitter**: `HOST_RESOLVED` parameters read through the matching accessor.
**Implementations stay in platform types** — `PriceImpl(price: String)`, `IconImpl(tint: Color?)`
— because the binding resolves before calling. The taste layer never sees a recipe.

**Surface migration**: `Price(price, leadingText, previousPrice, trailingText)`, `Badge(text)`,
`SectionHeader(title, description)`, `Chip(text)`, `TextInput(label, placeholder)` →
`TextValue`; `Icon(tint)` → `Color?`. Names and order unchanged ⇒ tags unchanged ⇒ the
lock passes. Segment version → 5.

**Compatibility overload**: one extra generated overload per component with ≥1 `TextValue`
parameter, all-`String`, delegating — so `Price(price = "x")` keeps compiling. Bounded at two
overloads per component. (`Color` gets no string overload: `tint = "primary"` compiling was
the hole, not a feature to preserve.)

**Skew**: a recipe arriving at an old host's `node.string()` reader is a `JsonArray`, not a
`JsonPrimitive` → the declared default renders. Degrade-don't-crash holds; a guest that must not
degrade branches on `segmentVersions["dogwood.designsystem"] >= 5`. Documented, not enforced.

**Tests** (≈ 14): the T1 list from the previous revision, plus: `Icon.tint` token resolves per
palette and re-resolves on the theme flip through a *generated* binding; an ARGB literal does
not follow the theme (the opt-out staying an opt-out); `colorOrNull` absence → host default.

**Estimate:** 2–3 days. **Risk:** low — every mechanism exists and was spiked or is already in
production in this repo; the work is making the generator's type system say what the runtime
already does.

### T1b. Recipes take guest-shipped parameters — "configurable over the wire", vetted

The factory *set* stays closed — the host must know what a recipe means, and an open set would be
remote code in a costume. But each factory becomes **parameterizable**, which is the dial that
ships new formatting behaviour over the air without a host release:

```kotlin
Formats.number(value, pattern = "#,##0.0")   // pattern is the GUEST's, shipped OTA;
                                             // separators are the DEVICE's
```

**Vetted (spike S-6):** `DecimalFormat(pattern, DecimalFormatSymbols(locale))` renders the guest's
pattern with the device's symbols — `1.234,6` in `de-DE`, `1,234.6` in `en-US` from the same
recipe — and a malformed guest pattern throws at construction, where it is caught and degraded to
the unpatterned form plus a `SkewReport` entry. A guest-authored pattern is untrusted input and is
treated like every other skew: wrong-looking beats crashed.

Dates deliberately do **not** take free-form patterns in v1: a date pattern that hard-codes field
order defeats the locale, and skeleton-based reordering (ICU `DateTimePatternGenerator`) exists on
Android but not in desktop `java.time`. Dates keep named styles (`short`/`medium`/`long`), with
skeletons recorded as an Android-capable extension for the ADR's assumptions section.

**Estimate:** ½ day, folded into T1's ADR.

### T2. Plural rules — as a formatting recipe, not a service

Unchanged from the previous revision: one more text factory; the guest ships the count **and its
own templates** (words stay payload-owned, exactly like `StringTable`); the host picks the CLDR
category for its locale and substitutes the formatted number.

```kotlin
Formats.plural(count = nights, templates = mapOf("one" to "# night", "other" to "# nights"))
// wire: [13, 3, {"one":"# night","other":"# nights"}]
```

Host category source: `android.icu.text.PluralRules` on Android; on desktop, `com.ibm.icu` or an
English-only fallback — decided in the ADR. **Estimate:** 1 day, after T1 (it returns
`TextValue`, so it lands everywhere T1 reaches).

**Deliberately deferred, and why:** caret placement (needs a declared-target design of the
ADR-014 kind), richer mask grammar (a grammar deserves its own record), message interpolation
beyond `#` (scope creep toward ICU MessageFormat — decide per need, not wholesale).

---

## Part 2 — Animation

Three additions, one shared principle: **the guest still only declares; nothing here lets the
guest read a per-frame value.** Claim A-2 stays true by design.

### A1. Animated colour

**Guest** (`Animation.kt`): no new type at all — `Color.animate(spec, onFinished): Color`
returns a colour whose recipe carries the animation, because an animated colour *is* a colour as
far as every parameter and modifier is concerned:

```kotlin
.background(Color.token(if (selected) "primaryContainer" else "canvas").animate(spring()))
```

Wire: `[14, colorExpr, spec, notify]` on the existing `BACKGROUND` element — factory 14
(`ANIMATED_COLOR`), disjoint from `COLOR_*` and `ANIMATED_NUMBER`. Because the animated form is
still a `Color`, T1's `Icon(tint: Color?)` gets animation eligibility for free the day the host
side of tint chooses to support it — one factory check, no new signature.

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
              onFinished: (() -> Unit)? = null): AnimationTarget
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
| 1 | **T0** the rename | Everything after it lands under final names, migrated never | ADR-021 (jointly with T1) |
| 2 | **T1 + T1b** host-resolved value types (`TextValue`, `Color`) and guest-shipped patterns | The destination-state rule reaches the whole generated surface; two latent generator defects ride along | ADR-021 |
| 3 | **A1** animated colour | Smallest animation item; exercises the recipe-in-recipe shape A3 also uses | ADR-022 (jointly with A3) |
| 4 | **A3** oscillate | Corrected design is fully specified; shares ADR-022 with A1 | ADR-022 |
| 5 | **A2** `Presence` | Largest; depends on nothing above but benefits from A1/A3's spec parsing being settled | ADR-023 |
| 6 | **T2** plurals | Returns `TextValue`, so it wants T1 landed first | ADR-024 |

Each lands with the full gate this phase has used: unit tests both sides, a device verification
pass (ADR-019's lesson — the harness types differently from a keyboard, and by extension animates
differently from a display), sample-screen usage so the feature is visible, and the spec/roadmap
rows updated. Total estimate: **7–9 working days**.

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
