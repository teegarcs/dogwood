# ADR-068: The generator refuses what it cannot bind, and a surface may declare an enumeration

**Date:** 2026-09-13
**Status:** Accepted

## 1. Context & Problem Statement

A production review of the adoption path ran a probe surface through the real generator rather
than reading it, and found two things a design-system team would hit in its first hour.

**A component the bindability rule rejects was dropped silently.** The generator printed one line
to standard output — `rejected Segmented.options: unclassifiable type: List<String>` — left the
component out of both the guest stubs and the host bindings, recorded it in the dictionary, and
exited zero. The build stayed green. [ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)
§2.2 had said since 2026-08-31 that "the build fails registration for a signature that violates
it, with an error naming the offending parameter"; nothing ever had. The parser's own comments
record the consequence: Umbra's `UmbraStepper` "was silently unbindable for as long as it existed,
and nothing noticed until a payload tried to call it".

**An enumeration parameter generated code that did not compile.** The classifier accepted any
capitalised single word as an ordinary value, so `variant: ButtonVariant` was classified `VALUE`
and emitted as `JsonPrimitive(it)` on the guest and `node.property(2)` on the host — neither of
which compiles for an enumeration, and both of which fail in a generated file with a message about
`JsonPrimitive` and nothing about the surface. `Dp` and `Alignment` failed the same way. An
enumeration is the most common non-primitive parameter shape in any design system — every
`variant`, `size`, `tone` and `emphasis` — so the documented advice to "use a `String`" was
asking adopters to erase their own type system at the boundary.

The same review found that `Long` and `Double` were listed as serializable and had no host-side
reader, so a `count: Long` compiled on the guest and handed the host binding a `JsonElement`.

## 2. Decision

### 2.1 An unbindable component fails the build, before anything is written

`dogwood-codegen` refuses to generate when any component on the surface has a parameter the rule
rejects. The failure names every offending parameter with its type and reason, states the allowed
types in one sentence, and names the switch that restores the old behaviour. Nothing is written
first: a refusal that left half-generated files behind would let a stale binding from the previous
run compile against the new surface, which is a subtler failure than the one being refused.

The old behaviour survives for one purpose. `--allow-unbindable true`, exposed by the plugin as
`allowUnbindable.set(true)`, is for **auditing a surface being written**, where seeing every
rejection at once is worth more than fixing them one build at a time. A rejected component is then
left out of both ends and recorded in the dictionary's `rejected` map, as before. Nothing that
ships should set it, and the property's own documentation says so.

### 2.2 A surface may declare an enumeration, and it crosses by name

A top-level `enum class` on the surface is read like a component: its name and its entries, in
declaration order. A parameter whose type names it is an ordinary value with one difference in
encoding — **the entry name crosses, never the ordinal.** Names survive a reordered declaration,
read as themselves in a render transcript and a skew report, and let a client one dictionary
version behind meet an entry it has never heard of and *say so*; an ordinal would silently resolve
to whichever entry sat at that index.

Both ends get the enumeration. The guest stubs carry a copy in the guest package, so a payload
writes `AcmeTag(tone = AcmeTone.Positive)` exactly as it would against the real design system. The
host bindings carry a copy too — **unless the surface says the adopter already owns one**:
`@Implementation("com.yourco.ds.Tone")` on the enumeration makes the binding decode into that
type instead, with the same rule as `@Implementation` on a component: host-side only, never in the
dictionary or on the wire, entry names must match, and the compiler says so when they do not.

An enumeration may also be an event argument. The host encodes `a0.name`; the guest resolves it
against its own entries and degrades to the first entry for a name it does not know, because a tap
from a host one version ahead must not crash the payload it was aimed at.

### 2.3 An unknown entry is skew, and takes skew's shape

The host readers `enum` and `enumOrNull` resolve a name against the entries this client was built
with. A name that is not there reads as the parameter's declared default, or the first entry when
it has none, and is recorded as `Component.parameter=Name` under a new report kind,
`UNKNOWN_ENUM_VALUE`. The first entry rather than a throw, because a throw lands inside
composition on every client that received the payload at once ([ADR-035](ADR-035-hostile-property-values.md));
and rather than nothing, because a required parameter has to be something and the surface author
put the ordinary case first.

### 2.4 The entries are part of the contract, and the lock knows

The dictionary records every enumeration and its entries. The lock refuses a removed or renamed
entry, and a removed enumeration, on the grounds it refuses a removed component: a payload built
against the old surface still sends the old name. Adding an entry is allowed and is a compatibility
event — a client behind the change renders the default where the payload meant something specific
— so the lock requires the segment version to move with it, exactly as for an added component.

### 2.5 Anything else is refused at the parse, in the surface's own terms

The catch-all that accepted any capitalised word is gone. A type that is not a serializable
primitive, a host-resolved type, `Modifier`, a slot, an event, a registered holder, or a declared
enumeration is rejected with a message that lists those categories and shows the enumeration
syntax. `Long` and `Double` gained the readers they were missing.

## 3. Rationale & Research

**The probe, verbatim.** A four-parameter surface through `SurfaceParser` and both emitters:

```
PROBE StyledButton.variant: type=ButtonVariant kind=VALUE rejection=null
PROBE StyledButton.sizes:   type=List<String>  kind=UNSUPPORTED rejection=unclassifiable type: List<String>
PROBE StyledButton.padding: type=Dp            kind=VALUE rejection=null
```

`variant` and `padding` were accepted and would not have compiled; `sizes` dropped the whole
component with the build green. The guest stub emitted for `variant` was
`set(variant) { recording.recorder.property(id, PropertyTag(2), JsonPrimitive(it)) }` — there is no
`JsonPrimitive(ButtonVariant)` overload — and the host binding read it as `variant = node.property(2)`.

**Why a name and not an ordinal.** [ADR-009](ADR-009-modifier-subsystem.md) §4 already records
the hazard for the hand-written alignment enumerations: "they cross as the guest enumeration's
ordinal, so reordering that enumeration is a wire-breaking change. It should be treated like a
tag." A generated enumeration is written by an adopter who has not read ADR-009, will reorder it,
and would get the wrong widget with no error. Names cost a few bytes per property and remove the
whole class of failure; the lock's append-only rule on entries covers what remains.

**Why fail before writing.** The Gradle task's outputs are the generated directories. A task that
fails after writing leaves the previous run's stubs beside the new dictionary, and the next
compilation consumes them without complaint. Refusing first means a red build has exactly one
cause.

**Evidence.** Ten generator tests (`EnumTest`), four entry-point tests driving the real `main`
(`RefusalTest`: the failure names the parameter and the allowed types, nothing is written, the
audit switch restores the old behaviour, a bindable surface is unaffected), three host reader tests
(`EnumReaderTest`: known name resolves, unknown name reads as the default and reaches the drain
under its own kind, absence is the sentinel and not skew). The Acme sample surface gained
`AcmeTone` and `AcmeTag(label, tone, onToneChange)`, its host implementation takes the generated
`AcmeTone`, the About screen composes it with a `remember`ed tone that a tap cycles — and the
generated code compiles for the Java Virtual Machine, WebAssembly and the iOS simulator, with the
lock advancing to `[AcmeTag, AcmeTone]` at version 2.

## 4. Unstated Assumptions

- **Assumes `Enum.entries` is available on every host target.** It is Kotlin 1.9 and the engine
  pins 2.3.20; a product on an older Kotlin cannot compile the generated bindings, which is
  already the supported-versions rule in `docs/getting-started.md`.
- **Assumes the first entry is an acceptable degradation for a required enumeration.** It is the
  declaration order the surface author chose, which is usually the ordinary case, and the report
  says when it was used. A surface where the first entry is the dangerous one should give the
  parameter a default or mark it `@Affordance` so the whole widget is withheld on unreadable skew.
- **Assumes an enumeration's entry names are the contract, not its ordinals or values.** An entry
  with a constructor argument parses, but nothing past its name reaches either side.
- **Assumes `allowUnbindable` stays off in anything that ships.** Nothing enforces it beyond the
  property's documentation; a check that refused the switch in a release build would need to know
  what a release build is, which the plugin does not.

## 5. Updated Documents

- [`developer-experience.md`](../../developer-experience.md) §4b — what a surface may declare; the
  refusal; enumerations.
- [`docs/getting-started.md`](../../docs/getting-started.md) §2 — the allowed parameter types and
  the enumeration syntax, with `@Implementation` on an enumeration.
- [`docs/authoring.md`](../../docs/authoring.md) — the skew rule for an unknown entry.
- [`docs/operating.md`](../../docs/operating.md) §7 — `UNKNOWN_ENUM_VALUE` in the skew report.
- [ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md) — the sentence
  that was not true, annotated.
- [`plans/adoption-audit.md`](../../plans/adoption-audit.md) — B7, the finding this closes.
- [`adrs/README.md`](../README.md) — index entry.
