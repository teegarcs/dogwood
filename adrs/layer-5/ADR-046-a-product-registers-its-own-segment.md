# ADR-046: A Product Registers Its Own Dictionary Segment

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

`specs/layer-5-host.md` lists this as item (c) of bespoke subsystem 9 and states the consequence in
one sentence:

> Without (c) a guest can emit only raw Material 3, which no product team ships.

[`plans/production-readiness.md`](../../plans/production-readiness.md) put it first for the same
reason: everything else on that list is smaller than it. The engine rendered on four hosts, passed
every conformance claim, and could not host a product.

The mechanism was one line. `RenderNode` called `bindDogwoodDesignSystem` **by name** — one segment,
decided at compile time, in this repository. Every mechanism built around it — the dictionary lock,
the affordance guard, skew reporting, the version vector — was parameterised by segment and had
exactly one caller, which is a different thing from being general.

Redwood's entire model was application-defined schemas. Deleting the schema must not delete the
escape hatch.

## 2. Decision

**Dispatch goes through a registry, and a product registers what the generator emitted from its own
surface.**

A product writes three things and no more:

1. a **surface** describing its components,
2. one `*Impl` per component — the part that requires taste,
3. a build file pointing the generator at the surface, with a **segment identifier**.

It writes no dispatch, no property decoding, no tag arithmetic and no skew handling. Those are
generated, by the same generator and the same emitters that produce Dogwood's own segment. A host
then makes one call:

```kotlin
DogwoodRegistry.register(AcmeDesignSystemBinding)
```

**The generator emits the binding as an object**, so registration is a value rather than a
convention: `segmentName`, `segmentVersion`, `tags`, `names`, and a composable `bind`.
`DogwoodDictionary`'s `knows`, `name` and `segmentVersions` all read the registry, **computed on
each read** rather than cached — a product registers at application start, which may be after
something first touches the dictionary, and a cached set would have answered "unknown" for every
product component that rendered before that.

**The registry refuses two things loudly**, because both fail silently otherwise and both render the
*wrong* widget rather than no widget:

- **A segment registered twice.** The ordinary way this happens is a host with several entry points
  registering in each. Registration belongs in `Application.onCreate`, and this is what makes the
  mistake a message.
- **Two segments claiming one tag.** This is the failure the dictionary lock **cannot see**: a lock
  is per surface, and two surfaces that each pass their own lock can still collide with each other.
  The symptom is not a missing widget — it is whichever registered first rendering the other's.

**Segment identifiers 0 and 1 are Dogwood's**, and `FIRST_PRODUCT_SEGMENT` says so in code.
`productTag(segmentId, localTag)` refuses the engine's, because catching it at a call site is
cheaper than catching it at registration and far cheaper than catching it on screen.

**There is no unregister, and registration is not dynamic.** A binding that disappeared while a tree
referencing it was on screen would turn rendered widgets into placeholders, which is worse than
anything it could fix. And a segment cannot arrive over the air: a binding is native code, and the
whole architecture rests on native code shipping through a store while payloads do not.

## 3. Rationale & Research

**Why a sample product, and why it is the gate.** `samples/product-design-system` is Acme's design
system — three components in `dev.acme.*`, a package nothing in `dev.dogwood` knows about. A
mechanism with one caller is not a mechanism, and the only way to find out which parts had quietly
assumed one caller was to add a second. Three of them turned up, none visible by reading:

- **The guest runtime's recording seam was `internal`.** `newWidget`, `recording`, `applyModifier`,
  `Children` and the wire form of a host-resolved value were all internal to `dogwood-compose` —
  correct while every generated stub landed in that same module, and impossible the moment a product
  generated stubs into its own. They are now public behind `@DogwoodGeneratedApi`, a
  `@RequiresOptIn` marker whose audience is exactly generated code. A guest author who reaches for
  it gets an error explaining that the thing they want is a component on their surface.
- **Generated code assumed it shared a package with the runtime.** It emitted `WidgetView` and
  `LayoutScope` unqualified. It now emits `import dev.dogwood.host.*` — and the guest half the
  equivalent — only when the target package differs, so Dogwood's own generated files do not carry
  a line whose only purpose is to be a no-op.
- **`--wire-out` was required.** It writes `DogwoodSegments`, the version vector every host reads
  including the Web one. A product segment passing it would overwrite the engine's constants with
  its own: not a compile error, not a wrong number, just the engine's segments disappearing. It is
  optional, and only Dogwood's own build passes it.

**A segment now has two names, and the reason is a real defect this change surfaced.** `segmentName`
names Kotlin declarations — `bindAcmeDesignSystem`, `AcmeDesignSystemTags` — and must be an
identifier. `wireName` is what a guest compares in `LocalSegmentVersions` and is conventionally
dotted. They were one field, reconciled by a `replace("dogwoodDesignSystem", "dogwood.designsystem")`
in the generator's entry point, and that held exactly as long as a segment named itself in one
place. The moment the binding object named itself too, the design system appeared in the version map
**twice, under both spellings**, and a guest branching on either would have been half right. The
design system is no longer listed by hand in `segmentVersions` at all: it is a registered segment
like any other and the registry supplies its version, which removes the second copy rather than
policing it.

### Verified on a device, because that is the only place the whole path exists

Pixel emulator, API 35, against the live delivery path. Acme's surface → generator → Acme's module →
segment 2 → guest stub → wire → registry → Acme's `*Impl`.

| Observation | Result |
|---|---|
| Acme's panel renders | purple bordered card, Acme's own corner and inset scale |
| `AcmePrice(129_900, "USD", emphasis = "strong")` | **$1299.00** in Acme's brand colour, bold |
| `AcmePrice(129_900, "JPY")` | **¥1299** — Acme's own currency rule, no minor units |
| Tap `AcmeAction` three times | "tapped 3 times" — three events crossed into a segment-2 handler |
| Tap the disabled `AcmeAction` | still 3; `@Affordance` on a product's surface behaves as on Dogwood's |

**The first attempt rendered nothing**, and the failure is the one worth recording: registration was
in `TabsActivity.onCreate` and the launcher is `SliceActivity`, so every Acme component came out as
an inert placeholder — no error, no crash, a screen merely missing something. That is registration's
failure mode in general, which is why the sample now registers in `Application.onCreate` and this
record says to.

Nine tests pin what a device shows expensively or not at all: that an unregistered segment is a
placeholder *and is reported*, that two product segments coexist, that a product's does not displace
Dogwood's, both refusals, that a product's version reaches the guest exactly once, and that a
product's component names itself in diagnostics.

## 4. Unstated Assumptions

- **The generator is still an internal Gradle project.** Acme consumes it as
  `project(":dogwood-codegen")`, which a real product cannot do. Publishing it — coordinates, a
  plugin marker, a supported way to point it at a source directory — is the remaining half of
  "a product can do this", and it is packaging rather than design.
- **Acme's guest stubs cross a module boundary as a source directory.** A real product would publish
  them as a Kotlin/JavaScript library. Both halves live in one repository here, and publishing a
  sample to a repository to consume it back would prove less rather than more.
- **Segment identifiers are allocated by hand.** Two products in one application must not pick the
  same number. The registry catches it at startup, which is the right place for a mistake nobody can
  make twice, and there is no central allocator.
- **A product's `*Impl` is host code and ships with the host.** A payload can use a component the
  installed client does not bind, and that degrades per the ordinary skew rules — placeholder,
  reported, and withheld if it owns an affordance. What it cannot do is arrive with its own binding.
- **Nothing generates an API reference for a product's surface.** The guest stubs are the reference,
  and reading generated Kotlin is not documentation.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 9, item (c).
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — Part 1.
- [`developer-experience.md`](../../developer-experience.md) — how a product adds components.
- [`adrs/README.md`](../README.md) — index entry.
