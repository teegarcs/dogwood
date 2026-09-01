# Layer 4: The Guest Runtime

## 1. Responsibilities & Scope

Layer 4 is where the delivered code actually runs. It is a sandboxed QuickJS interpreter hosting the developer's compiled Kotlin **and the real Compose runtime**. It executes composition and recomposition, and emits the resulting tree changes to [Layer 5](layer-5-host.md) as batched messages.

This is the layer that makes the architecture work: because genuine `androidx.compose.runtime` runs inside the guest, `remember`, `mutableStateOf`, `derivedStateOf`, `LaunchedEffect`, `CompositionLocal`, and recomposition all behave exactly as a developer expects, with no Dogwood-specific protocol for them.

**What it does:**
- Instantiates a QuickJS interpreter per experience via Zipline, and loads the validated bytecode from [Layer 3](layer-3-delivery.md).
- Runs a Compose `Composition` whose `Applier` materialises protocol nodes rather than pixels.
- Assigns stable identifiers and integer tags to nodes, properties, children slots, and event handlers.
- Batches all changes produced by one composition pass into a single outbound message.
- Receives inbound events, dispatches them to the correct guest lambda, and lets Compose recompose.

**What it does NOT do:**
- It does **not** lay out, measure, or draw anything. There is no Skia and no Compose UI in the guest.
- It does **not** know about Android or iOS. It has no platform Application Programming Interfaces (APIs).
- It does **not** decide what a widget looks like. It names a widget by tag; [Layer 5](layer-5-host.md) decides what that tag means.

## 2. Technical Stack & Dependencies

- **Interpreter:** QuickJS, embedded via [Cash App Zipline](https://github.com/cashapp/zipline). Zipline vendors stock Bellard QuickJS pinned at version `2021-03-27` (verified at `zipline/native/quickjs/VERSION`).
- **Compose runtime:** [`androidx.compose.runtime:runtime`](https://developer.android.com/jetpack/androidx/releases/compose-runtime), Kotlin Multiplatform, compiled for `js(IR)`. Confirmed in production use on a JavaScript guest by Cash App's Redwood.
- **Applier base class:** [`androidx.compose.runtime.AbstractApplier`](https://developer.android.com/reference/kotlin/androidx/compose/runtime/AbstractApplier). Redwood's equivalent is `NodeApplier<W> : AbstractApplier<Node<W>>` in [`WidgetApplier.kt`](https://github.com/cashapp/redwood/blob/trunk/redwood-compose/src/commonMain/kotlin/app/cash/redwood/compose/WidgetApplier.kt).
- **Boundary transport:** Zipline services (`ZiplineService`), whose calls are serialized with `kotlinx.serialization`.
- **Coroutines:** `kotlinx-coroutines-core`, for the composition scope and the frame clock.

## 3. Internal Architecture

```mermaid
flowchart TD
    Bytes["Validated bytecode (Layer 3)"] --> QJS["QuickJS instance (Zipline)"]

    QJS --> Guest["Guest module: developer code + dogwood-compose stubs"]
    Guest --> Runtime["androidx.compose.runtime"]

    Runtime --> Clock["MonotonicFrameClock"]
    Clock --> Recomposer["Recomposer"]
    Recomposer --> Composition["Composition"]

    Composition --> Applier["DogwoodApplier (AbstractApplier)"]
    Applier --> Tree["Node tree: WidgetNode / ChildrenNode"]

    Tree --> Recorder["ChangeRecorder"]
    Recorder --> Batch["ChangeBatch (ADR-004): sequence + List&lt;Change&gt;"]
    Batch --> Egress["DogwoodGuestService.sendChanges()"]
    Egress --> L5["To Layer 5"]

    L5 -.->|"Event(id, tag, args)"| Ingress["EventDispatcher"]
    Ingress --> Slots["Lambda slot table"]
    Slots --> State["Guest state mutation"]
    State --> Recomposer

    HostVer["Host dictionary version"] -.-> LocalVer["LocalDictionaryVersion CompositionLocal"]
    LocalVer -.-> Composition
```

### Diagram Node Definitions

* **Validated bytecode (Layer 3):** QuickJS bytecode whose signature and hashes have been verified.
* **QuickJS instance (Zipline):** A sandboxed interpreter with no ambient access to the filesystem, the network, or platform APIs. Every capability it has is one the host explicitly exposed. This is the security boundary, and it is the reason Google Play's dynamic-code policy exception applies: the guest reaches platform APIs only indirectly, through the host.
* **Guest module:** The compiled experience — the developer's `@Composable` functions plus the linked `dogwood-compose` stubs from [Layer 1](layer-1-authoring.md).
* **`androidx.compose.runtime`:** The genuine Compose runtime, compiled to JavaScript. It provides the `Composer`, snapshot state system, and recomposition machinery.
* **`MonotonicFrameClock`:** Compose's frame timing abstraction. The host drives it, so guest recomposition is paced by the device's real display refresh rather than free-running.
* **`Recomposer`:** Compose's scheduler. When guest state changes, it marks affected scopes invalid and re-executes only those on the next frame. This is why steady-state traffic is a small diff rather than a whole tree.
* **`Composition`:** One composition instance per mounted experience.
* **`DogwoodApplier` (`AbstractApplier`):** The heart of the layer. Compose calls `insertTopDown`, `insertBottomUp`, `remove`, `move`, and `clear` on it as the tree changes. Instead of manipulating UI objects, it manipulates protocol nodes. Redwood demonstrates the exact pattern, including the subtlety that widgets are attached to their parent on the *bottom-up* pass so that a node's initial properties are set before it is attached.
* **Node tree (`WidgetNode` / `ChildrenNode`):** The guest's mirror of the tree the host will build. `WidgetNode` represents one bound Compose call; `ChildrenNode` represents one slot within it, such as a `Column`'s `content`.
* **`ChangeRecorder`:** Translates node-tree mutations into protocol `Change` values — node creation, property updates, children insertions and removals, and modifier updates.
* **`ChangeBatch`:** All changes from one composition pass, accumulated and sent as one message with a monotonic sequence number ([ADR-004](../adrs/layer-4/ADR-004-change-event-protocol-v0.md)). **Batching is architecturally load-bearing.** Per-crossing cost is only acceptable because there are very few crossings; a design that crossed per node would not meet frame budget. Every working comparable system — Redwood, Zellij, WASM-4 — converges on this shape.
* **`DogwoodGuestService.sendChanges()`:** A `ZiplineService` method — the single egress point.
* **`EventDispatcher`:** Receives an inbound `Event(id, tag, args)` and resolves it to a guest lambda.
* **Lambda slot table:** Maps an `(id, EventTag)` pair to the actual Kotlin lambda captured during composition. When a developer writes `Button(onClick = { count++ })`, the lambda is stored here and the protocol carries only its tag.
* **Guest state mutation:** The lambda runs, mutating snapshot state, which invalidates recomposition scopes.
* **Host dictionary version / `LocalDictionaryVersion`:** The host's binding-dictionary state — a map of segment name to version ([ADR-006](../adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)) — supplied into the composition as a `CompositionLocal` so guest code can branch on client capability per segment. Redwood's `LocalWidgetVersion` is the proven (scalar) equivalent.

### The Frame Loop

The loop is **asynchronous and crosses two threads**. It is not five synchronous steps inside one frame, and describing it that way conceals the design's central constraint.

1. The guest requests a frame via `requestFrame()`. The host schedules one `Choreographer` (Android) or `CADisplayLink` (iOS) callback.
2. On that callback the host posts `frame(timeNanos)` to the **Zipline dispatcher** — a dedicated single thread.
3. The `Recomposer` re-executes invalidated scopes on that thread.
4. `dogwood-compose` stubs mutate the node tree through `DogwoodApplier`; the `ChangeRecorder` accumulates.
5. One `sendChanges` call is posted back to the **UI dispatcher**.
6. The host applies the batch and renders on its next vertical sync.

**Earliest possible pixel change is two vertical syncs after the input that caused it**, and neither thread hop is bounded, so the tail is long. Redwood's `AndroidTreehouseDispatchers` demonstrates this shape directly — `ui = Dispatchers.Main`, `zipline = ` a single-thread executor, with `checkUi()` and `checkZipline()` assertions on every protocol entry point.

### Invariant: No Per-Frame State in the Guest

Because the host's UI thread cannot call the guest during a frame traversal, **no state that must update every frame may live in the guest.** This is a hard design invariant, not a performance guideline, and it determines which Compose APIs are bindable at all ([Layer 5](layer-5-host.md)).

| Concern | Where state lives | What crosses the boundary |
|---|---|---|
| Animation | Host | A declarative target: "animate `alpha` to 1.0 over 300 ms, `FastOutSlowIn`" |
| Scroll offset | Host | Throttled `onViewportChanged(firstIndex, lastIndex)` — never per-pixel |
| Gestures | Host | Discrete semantic events: click, long-press, `dragEnded(velocity)` |
| Text field edit buffer | Host | Debounced value changes; the guest may reset the value but never echoes per keystroke |
| Application state and logic | Guest | Nothing — it stays with the developer's code |

A consequence worth stating: **the guest frame rate should be capped** — 30 Hz or 60 Hz — regardless of display refresh. Once animation and gesture are host-owned, nothing in the guest needs 120 Hz, and the question closes by construction rather than by hope.

Two further consequences the invariant carries, stated so they are chosen rather than discovered:

- **The declarative animation target is a whole subsystem, not a table row.** "Animate `alpha` to 1.0 over 300 ms" implies a protocol with springs, easings, interruption semantics, completion events, and time-varying `Modifier` values, and Compose's own `animate*AsState` surface must be *rejected* by [Layer 1](layer-1-authoring.md) until it exists — otherwise guest animations compile and silently tick the boundary per frame. See [Layer 5](layer-5-host.md) subsystem 7 and [ADR-005](../adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md).
- **Scroll-linked and drag-linked UI is forbidden by construction.** Any element whose per-frame value derives from scroll or drag position — parallax, custom collapsing headers, drag-progress indicators — has no representation, unless both ends of the linkage are host-side objects wired by a live-state holder (`TopAppBarScrollBehavior` collapsing a `TopAppBar`). The live-state Architecture Decision Record must define which linkages are expressible; anything beyond that would require a worklet-style binding language, which is deliberately **not** planned.

### Guest Memory Management

Zipline's QuickJS defaults are not tuned for a long-lived composition, and Layer 4 must set them deliberately. Measured from [`QuickJs.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/jniMain/kotlin/app/cash/zipline/QuickJs.kt): `gcThreshold = 256 KiB`, `memoryLimit = -1` (unbounded), `maxStackSize = 512 KiB`. **The stack figure is not what a Dogwood host inherits**, because [`Zipline.create`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/hostMain/kotlin/app/cash/zipline/Zipline.kt) immediately raises it: `quickJs.maxStackSize = 6 * 1024 * 1024L`, with the comment "Expect callers to use 8 MiB stack sizes for their calling threads." The effective default is therefore **6 MiB**, and the host thread that calls into the guest must itself be created with a large stack ([ADR-005](../adrs/layer-4/ADR-005-phase-0-harness-resolutions.md)).

- **`gcThreshold`.** QuickJS runs a full, non-generational, stop-the-world mark-and-sweep each time allocation grows by this much. Pause time scales with the *live* set, which here is the Compose slot table, every `MutableState`, the snapshot record chains, the node tree, and the lambda slot table. Raise it substantially (8–16 MB) and call `gc()` explicitly at idle and on memory-pressure callbacks.
- **`memoryLimit`.** Set a real limit with a defined recovery path — tear down and reload the experience — plus telemetry. Unbounded means any leak runs until the operating system kills the process.
- **`maxStackSize`.** Composition is deeply recursive, and interpreted frames are heavy. Zipline already raises the QuickJS setting to **6 MiB** and expects host threads to carry an **8 MiB** stack; Redwood independently found **8 MB** necessary, noting it was "experimentally found that's sufficient for our guest programs." Those are the only published datapoints for interpreted Compose composition depth. The Phase 0 harness ran the reference screen at 23 and 50 rows on Zipline's 6 MiB default with 8 MiB host threads and did not overflow; deeper trees are untested.

**Reclamation is not automatic.** `DogwoodApplier.remove()` and `clear()` must perform a depth-first purge of both the node map and the lambda slot table. Without it, a feed that creates and destroys ten thousand rows retains ten thousand closures, each capturing its row's data. Redwood does exactly this purge in `takeChanges()`.

## 4. Interfaces & Boundary

This is Dogwood's real cross-process boundary: a Zipline service boundary serialized with `kotlinx.serialization`. Services are named for the side that **implements** them.

**Implemented by the guest, called by the host:**
```kotlin
interface DogwoodGuestUi : ZiplineService {          // ZiplineService extends AutoCloseable
  fun start(
    host: DogwoodHost,
    services: DogwoodServices,                  // everything the sandbox can reach, Layer 5 ADR-013
    entryPoint: String,                         // which of the payload's experiences to run
    configuration: DogwoodConfiguration,        // the value at launch; pushed after -- see below
    launchParams: JsonElement,                  // serializable entry parameters, ADR-004 §2.5
    segmentVersions: Map<String, Int>,          // per-segment dictionary versions, ADR-006
    restoredState: StateSnapshot? = null,       // carried across a code update
  )
  fun sendEvent(event: Event)
  fun frame(timeNanos: Long)
  fun snapshotState(): StateSnapshot
  fun updateConfiguration(configuration: DogwoodConfiguration)
  override fun close()
}
```

**Implemented by the host, called by the guest:**
```kotlin
interface DogwoodHost : ZiplineService {
  fun sendChanges(batch: ChangeBatch)   // envelope with sequence number, ADR-004 §2.3
  fun requestFrame()
  fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag)
  fun onUnknownEventNode(id: Id, tag: EventTag)
  fun handleUncaughtException(exception: Throwable)
  override fun close()
}
```

`requestFrame()` is essential and was absent from an earlier draft. Without it the host must tick every vertical sync, costing a boundary crossing and a QuickJS wake-up per frame even while idle — which would contradict this layer's own batching argument. Redwood drives its frame clock this way: a `BroadcastFrameClock` whose `onNewAwaiters` callback calls `host.requestFrame()`.

**Threading contract.** `frame`, `sendEvent`, and all guest work run on the Zipline dispatcher; `sendChanges` is delivered to the UI dispatcher. Both sides should assert with check functions rather than rely on convention.

**The frame loop must wake for state the guest changes on its own.** Until host services existed, every guest state change began with a host call — a frame, an event, a configuration push — so sending apply notifications at the end of that call was sufficient. A **suspending** host service call breaks that assumption: the coroutine resumes long after the call that started it returned, writes state, and there is nobody left to notice. The guest therefore registers a global snapshot write observer that requests a frame, coalesced guest-side because the observer fires per write and a crossing per write would be a boundary call for every field a guest touches. It must be disposed with the composition, since it is registered globally and captures the host. Writes made *during* composition go to the composition's own snapshot and never reach a global observer, so it fires exactly for the case it exists for. This was found by observation rather than reasoning — the first suspending fetch logged a parsed response while the screen sat on a loading state ([Layer 5 ADR-013](../adrs/layer-5/ADR-013-host-services-and-entry-points.md) §2.4).

**Lifecycle.** Every service is closed through a `ZiplineScope`; teardown order is guest services first, then the `Zipline` instance. Zipline services that are not closed leak the guest-side proxy and its host reference.

**Configuration changes at runtime, and is pushed rather than pulled.** Density, layout direction, dark mode, safe-area insets, viewport size, and **locale** all move while a screen is live, so `DogwoodConfiguration` is not a one-shot launch parameter. An earlier draft of this specification declared it as a `Flow<DogwoodConfiguration>` parameter to `start`, mirroring Redwood's `UiConfiguration`. **The implementation pushes instead**, through `updateConfiguration`, and the reason is the threading contract: the value is *derived in host composition*, so it is already produced on the user-interface thread, and a push lets the crossing state which dispatcher it hops to and assert it got there. A long-lived flow across the boundary would have to be collected somewhere, and the somewhere is the thing this layer is careful about. The value is exposed to guest code as a `CompositionLocal`.

**An unchanged environment must cost nothing, and it is deduplicated twice.** The host drops an equal configuration before it crosses; the guest's backing value is snapshot state with the default structural-equality policy, so an equal value that does cross invalidates nothing and requests no frame. Both halves matter, because the host derives the configuration in composition and will therefore offer it far more often than it changes. See [Layer 5 ADR-012](../adrs/layer-5/ADR-012-host-environment-subsystem.md).

Locale is required twice over: for the resources subsystem ([Layer 5](layer-5-host.md), subsystem 8) and because the pinned QuickJS ships no ECMA-402 `Intl`, so guest-side locale-aware formatting has no built-in primitive. The dictionary state, by contrast, is fixed for a composition's lifetime and is passed at construction as a `staticCompositionLocalOf` — **a map of segment name to version** ([ADR-006](../adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)), not a scalar, so guest capability branching is per segment: `if (DogwoodSegments["acme.designsystem"] >= 3) ...`.

**What the boundary actually costs, measured.** Phase 0.3 has now run ([ADR-006](../adrs/layer-4/ADR-006-batch-crossing-is-guest-encoding.md)). For the reference screen's initial batch — 572 changes, 19,795 bytes — on a development machine faster than the gate device: the whole crossing is **24.06 ms** at p50, of which **guest-side encoding is 99.4%** and `CallChannel` transport is **1.0%**. The governing variable is **how much interpreted Kotlin runs while encoding**, not how many bytes come out: a bake-off over six wire formats ([ADR-007](../adrs/layer-4/ADR-007-v1-wire-format-positional-json.md)) found candidates that are smaller and slower, and a winner that is smaller *and* twenty times faster. Three consequences for anyone implementing this layer. First, **build the batch as native JavaScript values and let QuickJS's own `JSON.stringify` do the work** — positional JSON constructed that way crosses in **1.23 ms** where the same batch through `kotlinx.serialization` costs **24 ms**. Second, **do not reach for a binary format**: protocol buffers (+45%) and Concise Binary Object Representation (CBOR) (+908%) were measured and are slower, because their encoders are interpreted Kotlin and because `CallChannel` is a string channel that forces a Base64 surcharge on top. Third, steady state is cheap — a one-change batch crosses in 0.12 ms — so the expensive crossing is the *initial* batch, once per screen open, not the per-frame diff.

**Protocol value types.** The full field-by-field schema — the `Change` hierarchy, the `ChangeBatch` envelope, `Event`, sequence numbers, absence-as-default, and a worked wire example — is [ADR-004](../adrs/layer-4/ADR-004-change-event-protocol-v0.md) (provisional v0, binding for Phase 1). Every tag is an `Int`-backed value class and every dynamic value is a `JsonElement`; `WidgetTag` and `ModifierTag` carry their dictionary segment in the top 8 bits (ADR-004 §2.1):

| Type | Meaning |
|---|---|
| `Id(Int)` | A node instance. `Id(0)` is the root. **Monotonic; never reused within a composition.** |
| `WidgetTag(Int)` | Which bound Compose function a node represents. |
| `PropertyTag(Int)` | Which parameter is being set. |
| `ChildrenTag(Int)` | Which content slot children belong to. |
| `EventTag(Int)` | Which lambda parameter an event targets. |
| `ModifierTag(Int)` | Which modifier is applied. Scope-aware. |

**Guest failure must not tear the host tree, and it must be attributable.** Three rules. First, a `ChangeBatch` is emitted only after a composition pass completes — an exception mid-composition produces telemetry, never a partial batch, so the host tree is always a consistent frame. Second, on `handleUncaughtException` the host's recovery path is declared, not improvised: report with the payload version and manifest identifiers, tear down the experience, and either reload it or show the host's fallback surface. Third, **symbolication is an open item with real cost**: guest stack traces come from minified Kotlin/JavaScript inside QuickJS, and no source-map story exists yet — Layer 2 must retain the mapping artifacts per build, and a stack-trace translation step belongs in the telemetry pipeline before any dogfood. Until it exists, every guest crash report is line noise.

**Stale events are expected and must be handled.** The user can tap a node that the guest removed on a frame the host has not yet rendered. The guest must route unresolved events to `onUnknownEventNode` telemetry rather than crashing or silently ignoring them, and each `Event` should carry the change-batch sequence number it was rendered against so the guest can drop stale ones — a double-tapped "Pay" button across a frame boundary is a correctness problem, not a cosmetic one. Redwood handles the unknown-node case but has no sequence number; this is an opportunity to improve on the prior art. The sequence field is `Event.q` in [ADR-004](../adrs/layer-4/ADR-004-change-event-protocol-v0.md).

**Lifecycle events, partly designed.** Code update while a screen is live is the *normal* case, since `ZiplineLoader.load()` returns a `Flow`, and it is **implemented**: `DogwoodSession` collects that flow, captures the outgoing guest's `SaveableStateRegistry` through `snapshotState()`, tears it down, and restores into the replacement. Saved values cross the boundary as JSON, which is a real constraint on what a guest may declare saveable. **Backgrounding, process death, and memory pressure remain unaddressed**: the same snapshot mechanism is what they will use, but nothing persists a snapshot beyond the process today.

**What the sandbox may reach, it reaches through the service surface.** No filesystem, no sockets, no logger, no flags, no clock it can trust — a guest gets a `DogwoodServices` vendor at `start` with a nullable accessor per service, and a null means this client does not offer it. Absence is normal and guest code degrades rather than fails. `DogwoodNetwork.fetch` is `suspend` because a blocking fetch would stop composition, the frame clock and every pending event until the network answered, and the host implementation is a **policy point**: the payload is downloaded and replaceable over the air, so an open network service would be an exfiltration channel with the host application's name on it. See [Layer 5](layer-5-host.md), "The Host Service Surface".

- **Inputs:** A live `Zipline` instance from Layer 3; `Event` values; frame signals; a configuration flow.
- **Outputs:** Batched `List<Change>`; `requestFrame()` calls; telemetry.
- **Memory ownership:** The guest owns its heap inside QuickJS; the host cannot reach into it. Protocol messages are serialized copies. Closing the `Zipline` instance reclaims the guest heap.

## 5. Implementation Roadmap

1. **Milestone 1 — Compose runtime in QuickJS.** Prove that `androidx.compose.runtime` compiled to Kotlin/JS runs inside Zipline's QuickJS at all, by driving a trivial composition that mutates a counter and observing recomposition. **This is the single highest-risk unknown in the layer and must be done first.**
2. **Milestone 2 — Applier and node tree.** Implement `DogwoodApplier` over `AbstractApplier`, with `WidgetNode` and `ChildrenNode`. Validate insert, remove, move, and clear against the hand-written stub slice from Layer 1.
3. **Milestone 3 — Change recording and batching.** Emit `Change` values, batch per composition pass, and assert that an idle composition produces no traffic.
4. **Milestone 4 — Event round trip.** Implement the lambda slot table and inbound `Event` dispatch. Prove an `onClick` mutates guest state and produces a minimal diff. **Include the wrapper-scoping test** ([ADR-006](../adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)): a state change three guest-defined wrapper layers deep must cross the boundary as a single `PropertyChange`, not a re-emit of the wrappers — this is the property that makes developer-defined composables free, and it must be demonstrated, not assumed.
5. **Milestone 5 — Frame clock integration.** Drive the guest `MonotonicFrameClock` from the host's display link or `Choreographer`.
6. **Milestone 6 — Performance measurement.** Measure, on-device: per-crossing `sendChanges` latency for realistic batch sizes, composition and recomposition cost inside QuickJS, and total frame cost against budget. **These numbers are currently unproven and are the gating evidence for the whole architecture.** Explicitly probe the QuickJS rope-string defect by confirming generated guest code performs no `StringBuilder`-heavy work on the hot path.
