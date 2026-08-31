# ADR-004: The `Change`/`Event` Protocol Shape (Provisional v0)

**Date:** 2026-08-31
**Status:** Proposed — **v0 is binding for Phase 0.3 and Phase 1**; Phase 1's measurements (host mirror vs. imperative applier, batch-size costs) feed the v1 revision. Marked provisional so nobody mistakes it for a frozen wire contract, and written now because two roadmap items silently depended on a schema that did not exist: experiment 0.3 cannot encode "a realistic change batch" without an encoding, and Phase 1 step 4 said "implement the batched `Change` protocol" when no field-level definition existed to implement.

## 1. Context & Problem Statement

[Layer 4](../../specs/layer-4-sandbox.md) defined the protocol only narratively: six `Int`-backed tag value classes, values as `JsonElement`, "one batch per composition pass," and change kinds implied in prose (create, property update, children insert/remove/move, modifier update). Handoff review found no field lists, no batch envelope, no `Event.args` format, no sequence-number field (despite the stale-event design requiring one), and no wire example anywhere in the repository. Separately, [Layer 5 ADR-006](../layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md) introduced dictionary segments but specified their tag encoding only as an assumption ("segment identifier + local tag, sized generously").

## 2. Decision

### 2.1 Tag and segment encoding

- **`WidgetTag` and `ModifierTag` carry their segment in the top 8 bits**: `tag = (segmentId shl 24) or localTag`, giving 256 segments × 16,777,216 local tags. Segment identifiers are assigned by the dictionary composition, stable per client build: `0` = the generated androidx tier (`foundation-layout` first in generator v1), `1..255` = registered modules in registration order, recorded by name in the dictionary artifact so order changes never re-map an existing segment.
- **`PropertyTag`, `ChildrenTag`, and `EventTag` are widget-scoped plain integers** — they identify a parameter *of* a widget whose `WidgetTag` already names the segment, so they carry no segment bits. Numbering is parameter-declaration order in the parsed model, which makes tags stable under the additive-optional evolution rule (new optional parameters append).
- Hand-written Phase 1 stubs use segment `0` for the layout primitives and segment `1` for the design-system slice, with local tags `1..n` in the order listed in the roadmap — so Phase 1 genuinely exercises two segments with no generator present.

### 2.2 The `Change` hierarchy, field by field

All types are `@Serializable` Kotlin classes; the wire format is `kotlinx.serialization` JSON (class discriminator `"k"`, single-letter field names — the payload crosses `CallChannel` as a string and 0.3 measures bytes, so names are short by design).

```kotlin
@Serializable sealed interface Change

@Serializable @SerialName("c") // Create
data class Create(
  val i: Id,          // new node id; monotonic, never reused in a composition
  val w: WidgetTag,   // segment-encoded widget tag
) : Change

@Serializable @SerialName("p") // Property set
data class PropertySet(
  val i: Id,
  val p: PropertyTag,
  val v: JsonElement, // by-value / value-class / deferred-expression encoding; JsonNull = Kotlin null
) : Change
// Unset optional parameters are ABSENT (no PropertySet is sent) — absence IS the
// "use host default" sentinel from overview §7 item 4. Resetting a previously-set
// property back to its default sends v = {"$d":1}.

@Serializable @SerialName("m") // Modifier chain replace (whole chain, ordered)
data class ModifierSet(
  val i: Id,
  val e: List<ModifierElem>, // ModifierElem(t: ModifierTag /* segment-encoded */, v: JsonElement)
) : Change

@Serializable @SerialName("a") // Child add
data class ChildAdd(
  val i: Id,           // parent
  val s: ChildrenTag,  // which slot
  val c: Id,           // child node id
  val x: Int,          // index within slot
) : Change

@Serializable @SerialName("r") // Child remove; guest performs the depth-first purge
data class ChildRemove(val i: Id, val s: ChildrenTag, val x: Int, val n: Int) : Change

@Serializable @SerialName("v") // Child move
data class ChildMove(val i: Id, val s: ChildrenTag, val f: Int, val t: Int, val n: Int) : Change
```

### 2.3 The envelope and the event

```kotlin
@Serializable
data class ChangeBatch(
  val q: Int,             // batch sequence number, monotonic from 1 per composition
  val g: List<Change>,    // all changes from one composition pass, apply-in-order
)

@Serializable
data class Event(
  val i: Id,              // target node
  val e: EventTag,        // which lambda parameter
  val q: Int,             // sequence number of the last batch the host had APPLIED when
                          //  the interaction occurred — the staleness check from Layer 4 §4
  val a: List<JsonElement> = emptyList(), // lambda arguments, in declaration order
)
```

`DogwoodHost.sendChanges(changes: List<Change>)` becomes `sendChanges(batch: ChangeBatch)`; `DogwoodGuestUi.sendEvent(event: Event)` is unchanged in name and now has a defined payload. Apply-in-order within a batch is mandatory; the placeholder-node rule for unknown `Create` (overview §6) exists precisely so later index arithmetic in the same batch stays consistent.

### 2.4 Worked wire example

Guest composes `Column { Text("Total"); Text("$8.00"); PrimaryButton("Pay", onClick=…) }`. Segment 0 local tags: `Column=1` (children slot `content=1`), `Text=2` (property `text=1`). Segment 1: `PrimaryButton=1` (property `label=1`, event `onClick=1`). Root is `Id(0)` with slot `1`. First batch:

```json
{"q":1,"g":[
  {"k":"c","i":1,"w":1},        {"k":"a","i":0,"s":1,"c":1,"x":0},
  {"k":"c","i":2,"w":2},        {"k":"p","i":2,"p":1,"v":"Total"},   {"k":"a","i":1,"s":1,"c":2,"x":0},
  {"k":"c","i":3,"w":2},        {"k":"p","i":3,"p":1,"v":"$8.00"},   {"k":"a","i":1,"s":1,"c":3,"x":1},
  {"k":"c","i":4,"w":16777217}, {"k":"p","i":4,"p":1,"v":"Pay"},     {"k":"a","i":1,"s":1,"c":4,"x":2}
]}
```

(`16777217` = segment 1 &lt;&lt; 24 | local 1.) The user taps the button after the host applied batch 1:

```json
{"i":4,"e":1,"q":1}
```

Guest state mutates, recomposition changes one string; the steady-state diff is one batch: `{"q":2,"g":[{"k":"p","i":3,"p":1,"v":"$9.00"}]}`.

### 2.5 Minimal entry-point contract for Phase 1 (seed of subsystem 9(a))

Until [Layer 5](../../specs/layer-5-host.md) subsystem 9 is designed: the manifest's `mainFunction` registers the experience's single entry composable by name; `DogwoodGuestUi.start(host, configuration, launchParams: JsonElement)` carries serializable launch parameters the host constructs; and **host-directed callbacks (the `onConfirm: (Order) -> Unit` shape in the developer-experience example) are not supported in Phase 1** — the guest signals outcomes by calling an ordinary host Zipline service. This is deliberately minimal and is superseded by the subsystem 9 ADR, not evolved piecemeal.

## 3. Rationale & Research

The shape is Redwood's protocol, adapted: Redwood's `Change` sealed hierarchy (`Create`, `ValueChange`, `ChildrenChange.Add/Remove/Move`, `ModifierChange`) shipped and is the proven decomposition ([`redwood-protocol`](https://github.com/cashapp/redwood/tree/trunk/redwood-protocol/src/commonMain/kotlin/app/cash/redwood/protocol)); Dogwood's deltas are the segment-encoded tags (ADR-006), the batch/event **sequence numbers** (Layer 4 §4 requires them for stale-event drops and double-tap correctness; Redwood has none — recorded there as an improvement on the prior art), and absence-as-default-sentinel (overview §7 item 4: most defaults are `@Composable` and must be host-resolved, so the wire must never carry a guest-computed default). Short field names exist because [Layer 4 ADR-002](ADR-002-adopt-zipline-quickjs-substrate.md) established the cost that matters is per byte.

## 4. Unstated Assumptions

- **Assumes JSON via `CallChannel` for v0.** If 0.3's byte counts blow the ≤ 4 ms gate leg, the v1 revision considers a binary encoding — the hierarchy is transport-agnostic; only §2.4's rendering changes.
- **Assumes whole-chain `ModifierSet` replacement is acceptable at v0.** Per-element diffing is a v1 optimization to be justified by 0.3's numbers, not assumed.
- **Assumes 8/24 tag packing is generous enough**: 255 registered segments and ~16.7 million local tags per segment exceed any plausible catalog.
- **Assumes `q` fits `Int`**: 2³¹ batches at 60 Hz is over a year of continuous recomposition; compositions do not live that long.

## 5. Updated Documents

- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — §4 interfaces reference this schema; envelope/sequence semantics; entry-point paragraph
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — segment/tag encoding under subsystem 9; `HostChangeApplier` applies this hierarchy
- [roadmap.md](../../roadmap.md) — Phase 0.3 encodes this schema (labeled provisional); Phase 1 step 4 implements it; Phase 1 gains the entry-point step
- [developer-experience.md](../../developer-experience.md) — the entry-point/callback caveat on the worked example
