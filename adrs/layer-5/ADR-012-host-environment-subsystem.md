# ADR-012: The Host Environment Subsystem — Derived in Composition, Pushed Across, Resolved Against a Palette

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4 lists nine bespoke subsystems and puts **host environment** first, calling it
"the cheapest, and everything else assumes it". The reason everything else assumes it is not
convenience. A guest composition runs inside QuickJS: it has no display metrics, no resources, no
system settings, no window, and — because the pinned QuickJS build ships no ECMA-402 `Intl`
(International application programming interface) — no locale-aware formatting primitives of its
own. Every fact about the device that a composition may read has to arrive through one path or it
does not exist.

Before this decision, `DogwoodConfiguration` existed as a data class and
`DogwoodGuestUi.updateConfiguration` existed as a method, but nothing on the host derived a
configuration from anything, nothing ever called `updateConfiguration`, and the sample computed a
value once from `Resources.getDisplayMetrics()` and never touched it again. Three specific
consequences followed:

1. **The screen metrics were the wrong measurement.** `displayMetrics.widthPixels` is the
   *screen*. It is wrong in split screen, wrong on a foldable's inner display, wrong in a
   resizable desktop window, and wrong when the experience is mounted in a side pane — and wrong
   silently, because a layout computed for a viewport that is 40% too wide still renders.
2. **Nothing changed at runtime.** Rotation, a font-scale change, a locale change, a theme switch:
   none of them reached the guest. On Android the activity was destroyed and recreated instead,
   which tore down the QuickJS instance, refetched the guest and lost its state — to redisplay a
   screen the guest could have re-laid-out in place.
3. **Dark mode had nowhere to land.** `Palette` was an `object` of thirteen hard-coded light
   colours, read directly by every binding implementation and by the deferred-expression
   evaluator's token table. There was exactly one palette, so `Colors.token("primary")` — the
   mechanism ADR-010 introduced precisely so a guest could name an intent and let the host resolve
   it in theme — resolved to the same colour in every theme, which is the thing it exists not to
   do.

## 2. Decision

The host environment is **derived in composition, pushed across the boundary, and — for colour —
resolved host-side against a palette the guest never sees.**

**Derivation** is common Compose Multiplatform code in `dogwood-host/Environment.kt`, so it is the
same derivation on Android, desktop, Web and iOS:

- `DogwoodEnvironment(modifier, darkMode, windowInsets) { configuration -> … }` measures the slot
  the experience occupies with `BoxWithConstraints`, provides `LocalPalette`, and hands the
  derived configuration to its content.
- `rememberDogwoodConfiguration(viewportWidthDp, viewportHeightDp, darkMode, windowInsets)` reads
  `LocalDensity` (density and font scale, kept separate), `LocalLayoutDirection`,
  `androidx.compose.ui.text.intl.Locale.current`, and the supplied `WindowInsets`, converting
  insets to density-independent pixels at the boundary. It `remember`s on its own contents, so an
  unrelated recomposition of the caller does not manufacture a fresh-but-equal value.

**Delivery** is a boundary crossing and therefore has a dispatcher.
`DogwoodExperience.updateConfiguration` hops to the Zipline dispatcher and asserts it arrived
there. `DogwoodSession.updateConfiguration` additionally **retains** the value, so a guest adopted
mid-session by a code update starts with the environment the device is actually in rather than the
one captured when the session was constructed.

**Deduplication happens twice, deliberately.** The session drops an equal configuration before it
crosses; the guest's `configuration` is snapshot state with the default structural-equality policy,
so an equal value invalidates nothing even if one does cross. This is what makes it safe for a host
to push the environment from composition — where it is derived — rather than trying to work out for
itself whether it moved.

**Colour becomes a value, not a constant.** `Palette` is now a class with `Light` and `Dark`
instances and a `token(name): Color?` lookup, published through a dynamic `LocalPalette`. Binding
implementations read `palette()`. `ExpressionEvaluator.color` takes the palette as a parameter and
clears its memo when the palette identity changes, because a token recipe evaluates to a different
colour in a different theme and a memo keyed on the recipe alone would repaint the screen in the
old theme with no other symptom.

**Two derived views live in `dogwood-protocol`,** not on either side: `WidthClass`
(Compact / Medium / Expanded, at Google's published Material breakpoints of 600 and 840
density-independent pixels) and `language`. They are shared because a guest laying out for
"compact" and a host measuring "compact" must mean the same thing; if the breakpoints drifted
apart the disagreement would be invisible until somebody reported a layout bug on one device.

**Inset consumption is declared, not inferred.** `DogwoodEnvironment` takes `windowInsets` as a
parameter, because a composable that reads `WindowInsets.safeDrawing` sees the *full* window insets
even when an ancestor has already padded for them. A host that has already inset the slot says so;
the sample does exactly this, padding its own status banner for the top inset and passing only the
bottom inset down. Getting this wrong double-counts and leaves a band of dead space.

This asymmetry is the sharp edge of the subsystem, and it was **measured rather than assumed**. With
the sample's banner column wrapped in `Modifier.windowInsetsPadding(safeDrawing.only(Top))` and the
environment reading full `safeDrawing`, the emulator reported `426×868dp … safe 52/24`: the
*viewport* had already shrunk from 920 to 868 density-independent pixels — `BoxWithConstraints`
measures the slot it is actually given, so it sees the consumption — while the *inset* still read
52, the full status-bar height the ancestor had already paid for. Two ambient facts about the same
padding, disagreeing. Passing `safeDrawing.only(Bottom)` produced the correct `safe 0/24`.

**On Android, the sample declares `android:configChanges`** for orientation, screen size, screen
layout, smallest width, `uiMode`, density, font scale, locale and layout direction. This is the
whole point of the subsystem made concrete: Dogwood handles these changes itself, in place, with
the QuickJS instance and the guest's state intact.

## 3. Rationale & Research

**Why `BoxWithConstraints` rather than screen metrics.** The measurement a guest needs is the space
it has, not the space the device has. `BoxWithConstraints` reports the incoming constraints of the
slot it is placed in, which is the correct quantity in every case listed in §1.1, and it is common
Compose Multiplatform API:
[`BoxWithConstraints.kt`](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/BoxWithConstraints.kt).

**Why every field comes from an ambient rather than a platform application programming
interface.** `LocalDensity`, `LocalLayoutDirection`,
[`androidx.compose.ui.text.intl.Locale.current`](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/intl/Locale.kt),
`isSystemInDarkTheme()` and `WindowInsets.safeDrawing` are all `commonMain` in Compose
Multiplatform. Using them keeps the derivation in common code, which is the same argument
[ADR-004](ADR-004-compose-multiplatform-sole-host-target.md) makes for the host as a whole: one
implementation reaching every platform is the reason the host renders through Compose
Multiplatform at all.

**Why locale crosses as a tag and not as formatted output.** The pinned QuickJS
(2021-03-27, via Zipline 1.27.0) has no `Intl`, so the guest cannot format a date, a number or a
currency in a locale-aware way even if it knows the locale. The tag lets the guest *branch*
(choose a string, choose a layout), which is the half it can do; formatting is host work and
belongs to the resources subsystem. Saying this plainly matters more than the field itself — a
reader who assumes `Intl` exists will write guest code that silently produces United States
formatting for every user.

**Why density and font scale are separate fields.** A user who enlarges text has not enlarged
everything. Conflating the two produces layouts that break for exactly the users who most need
them not to.

**Why the width breakpoints are Google's.** 600 and 840 density-independent pixels are the
published Material window size classes, which Compose Multiplatform's own `WindowSizeClass` uses:
<https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes>.
Inventing breakpoints would mean a Dogwood guest and the host application around it disagreeing
about what "tablet" means on the same screen.

**Why the layout direction crosses as a boolean.** An enumeration ordinal would make adding a
third layout direction, if one ever exists, a silent renumbering of the wire format — the same
hazard [ADR-011](ADR-011-generator-emits-the-bridge.md) locks tags against.

### Verified end to end, not asserted

Run on the Pixel 9 Pro emulator (API 35) against the live delivery path, with the guest served over
the network and signature-verified:

| Observation | Result |
|---|---|
| Portrait | banner reports `426×868dp (Compact) · light · en-US · text ×1.0 · safe 0/24`; destination cards 220 dp, stay thumbnails 96 dp |
| Landscape, **live** | `952×342dp (Expanded)`; cards 340 dp, thumbnails 128 dp |
| Guest reloads across that rotation | **zero** — the QuickJS instance, the composition and the guest's `rememberSaveable` state all survived |
| Dark mode | whole screen repaints against `Palette.Dark`; the guest's `background(Colors.token("primary"))` accent bar re-resolves to the dark palette's lighter blue |
| Safe area with the host consuming the top inset | `safe 0/24` — top correctly zero, bottom the gesture bar |

Guest-side unit tests pin the properties the screenshots cannot: that the environment is present
for the *first* composition (not shortly after it), that one change costs exactly one batch, that
it costs only the nodes that read it (one `PropertySet`, not a re-emit), that an equal
configuration produces neither traffic nor a frame request, and that safe areas arrive already
converted to density-independent pixels.

## 4. Unstated Assumptions

- **`BoxWithConstraints` costs a measure pass.** It defers its content to layout, so the
  environment is available one measure pass in. This is invisible here because the experience is
  mounted inside it, but a host that wanted the configuration *before* laying anything out would
  need a different derivation.
- **Inset consumption is invisible to composition, and this was verified on Compose Multiplatform
  1.10.3 / androidx foundation-layout as pinned here** (the experiment is in §3). It is a
  behaviour, not a contract: if a future release exposes consumption to composition reads, the
  `windowInsets` parameter becomes a convenience rather than a requirement, and a host that keeps
  passing an explicit value stays correct either way.
- **The palette memo relies on palette *identity*.** `Palette.Light` and `Palette.Dark` are
  singletons, so `!==` is a correct and cheap invalidation test. A host that constructed a fresh
  `Palette` per composition would defeat the memo — it would still be *correct*, but it would
  rebuild every colour every frame.
- **Assumes the host application is willing to declare `configChanges`.** An application that
  lets Android recreate its activity still works; it simply pays a full guest reload for every
  rotation, and loses anything not declared `rememberSaveable`.
- **Dark mode is a boolean, not a theme identity.** A product with three themes, or a per-brand
  palette, needs a richer field. Nothing here forecloses that; the field would grow and
  `LocalPalette` would be provided from it.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — new "The Host Environment" section under
  the bespoke subsystems, with its diagram and node definitions; `Palette` described as a value.
- [`specs/layer-4-sandbox.md`](../../specs/layer-4-sandbox.md) — the configuration crossing and its
  dedupe rule recorded in the interfaces section.
- [`roadmap.md`](../../roadmap.md) — Phase 4's host-environment row marked delivered, with what was
  and was not covered.
- [`adrs/README.md`](../README.md) — index entry.
