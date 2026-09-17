# ADR-076: The preview is a second back end, not a translation

**Date:** 2026-09-16
**Status:** Accepted

## 1. Context & Problem Statement

[Layer 1](../../specs/layer-1-authoring.md) has said since the first draft that an authoring module
"is compiled twice from one source set": to Kotlin/JavaScript for deployment, where
`dev.dogwood.compose` is a set of recording stubs that emit wire operations, and to the Java Virtual
Machine (JVM) for previews, where the same calls reach real Compose.
[`developer-experience.md`](../../developer-experience.md) §4 has said since 2026-09-09 that this
does not work, because `dogwood-compose` declares `js(IR)` and no other target.

Three questions had to be answered to build it, and Layer 1 Milestone 3 left all three open.

1. **How does one source compile against two implementations?** Layer 1 says "the preview `actual`s",
   which implies `expect`/`actual` — a Kotlin Multiplatform mechanism that would require every guest
   declaration to be an `expect` in a shared source set and every payload to be a multiplatform
   module.
2. **Who writes the Java Virtual Machine (JVM) side?** For the generated tiers
   ([ADR-072](../layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md))
   `plans/generator-v2.md` milestone M6 says the generator should emit it from the same parse. It
   does not, and the primitive tier and the design system have no generator at all.
3. **What does a preview do with the things only a host can answer** — a colour token, a formatted
   currency, an icon by name, a platform picker, a live service bridge?

## 2. Decision

**The preview is a second back end publishing the same package, not a translation layer and not an
`expect`/`actual` pair.** A new module, `engine/dogwood-compose-preview`, declares
`dev.dogwood.compose` and `dev.dogwood.compose.material3` with the same names and the same
signatures as the deployment stubs, and bodies that call `androidx.compose` directly. A payload's
preview compilation puts that module on its classpath instead of `dogwood-compose`; the payload's
source is unchanged and contains no `expect`, no `actual` and no platform folder.

**The Java Virtual Machine (JVM) surface is hand-written for now, and derived rather than invented
for the generated tier.** The Material 3 delegates were produced mechanically from the two surfaces
the generator already emits — the guest stub supplies the signature, the host binding supplies the
argument mapping and the library default for every omittable parameter — and committed. The
generator emitting them on every build remains M6's proper close and is not done.

**A registered design system previews as itself.** Acme's preview delegates call the real
implementations in `samples/product-design-system`, the module a host already depends on.

**Every host-only answer is a declared, visible stand-in.** A colour token resolves against a fixed
Material 3 colour scheme and renders magenta when the palette does not carry it; `Formats` uses the
machine's own locale; an icon and a remote image draw a labelled placeholder box; a platform picker
draws a box naming what the host would have answered; host services are a preview stand-in whose
clock is this machine's and whose network is absent. None of them guesses quietly.

**Step 1 only.** The desktop window is built and graded. The Android Studio pane — which Layer 1
records needs an Android library variant, because Android Studio renders previews through Layoutlib
— is **not built and has not been seen**.

## 3. Rationale & Research

**Why not `expect`/`actual`.** `expect` declarations must live in a common source set of the *same*
module as their `actual`s, so every payload would have to become a Kotlin Multiplatform module with
a `commonMain` containing Dogwood's entire vocabulary — the opposite of the "a payload is ordinary
Compose" claim §4b makes. Two modules publishing the same fully-qualified names and chosen by
classpath needs nothing from the language: the JVM compilation of `samples/slice-screens` lists
`dogwood-compose-preview`, the JavaScript compilation lists `dogwood-compose`, and
`MaterialScreen.kt` compiled for both without a single edit. That is the evidence: the file is in
`engine/samples/slice-screens/src/jsMain/kotlin/dev/dogwood/slice/MaterialScreen.kt` and is named by
`kotlin.include` in both compilations.

**Why derive the Material 3 delegates rather than write them.** The library's defaults for omitted
parameters are the hard part — `ButtonDefaults.shape`, `AssistChipDefaults.assistChipBorder(enabled)`,
`TabRowDefaults.ScrollableTabRowEdgeStartPadding` — and the generator has already computed every one
of them, because a host binding must quote the same default to rebuild the call
([ADR-068](../layer-5/ADR-068-the-generator-refuses-what-it-cannot-bind.md) is why it can be trusted
to refuse what it cannot). Reading them out of
`build/generated/dogwood-material3/host/**Bindings.kt` makes the preview agree with the host by
construction rather than by a second reading of the library.

**Why a preview cannot be the gate for the things that matter.** One Compose runtime has one
dictionary and no boundary, so neither per-frame crossing nor version skew can occur in it. This is
not a limitation of the implementation; it is what "no protocol between the source and the screen"
means. `tools/skew-drill/` and the pre-flight drills grade those, on clients.

**Why the window is graded headlessly.** A window cannot be asserted on by a machine.
`-Pheadless` composes the screen into an off-screen Skia surface and renders one frame, exiting
non-zero on a throw. It was watched to fail: a deliberate `error(...)` in the `SectionHeader`
preview delegate took both screens down, and its first arrangement — which composed the Material
catalogue's ten sections but not the catalogue screen itself — did **not** catch it, which is why
the headless pass now composes both. A second experiment, a delegate passing a negative size,
did *not* fail: Compose coerces it. The gate's reach is a crash, not a wrong picture.

## 4. Unstated Assumptions

- **The two back ends stay in step by nothing but discipline.** There is no check that
  `dogwood-compose` and `dogwood-compose-preview` declare the same names. A stub added to one and
  not the other is found when a payload calls it and the preview compilation fails — loudly, but
  late. A generated JVM surface (M6's proper close) removes this assumption for the tiers; the
  primitive tier and the design system keep it.
- **The Material 3 delegates are frozen at the dictionary they were derived from.** Assumes a
  regenerated tier's new components are either unused by the previewed screens or added here by
  hand.
- **Assumes `ImageComposeScene` remains available in Compose Multiplatform's desktop artifact**; it
  is what renders the headless frame, and it is experimental.
- Assumes the preview module's coverage is "what the two catalogue screens use". `ExploreScreen`
  and `FeedScreen` reach further — host networking, lazy windowing, the pager — and are not on the
  preview path.
- Assumes an Android library variant would drive the Android Studio pane. **This is Layer 1's
  recorded reading of Layoutlib's requirements and has not been executed**, which is why the pane is
  described as not built rather than as nearly built.

## 5. Updated Documents

- [`developer-experience.md`](../../developer-experience.md) §4, rewritten from "planned, not built"
  to what the preview shows, what it stands in for, what it can never show, and what remains
  unbuilt; and §8's comparison table row, which still said "planned, not built".
- [`specs/layer-1-authoring.md`](../../specs/layer-1-authoring.md) — **not yet updated.** Milestone 3
  still describes the preview path as a decision to be taken and as `expect`/`actual`; this decision
  answers both and the specification should follow.
- [`plans/close-the-backlog.md`](../../plans/close-the-backlog.md) Group 4 — **not yet updated.**
  Step 1's "done means" asks for a screenshot in the manual beside the same screen rendered through
  the protocol; the repository carries no binary files, so §4 documents the one-line command that
  reproduces the picture instead, and the side-by-side pair is not written.
