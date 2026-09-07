# Dogwood Engine

The implementation. `tools/phase0/` measures; this runs.

**Status: Phase 1 in progress.** A real screen, authored as ordinary Kotlin Compose, compiled
to Kotlin/JavaScript, executed inside Zipline's QuickJS, crossing as a change batch, and
rendered by native Compose Multiplatform — responding to taps. Several named Phase 1
deliverables are outstanding; see "What is not done" below.

## Modules

| Module | Runs on | Contents |
| --- | --- | --- |
| `dogwood-protocol` | everywhere | The wire types of [ADR-004](../adrs/layer-4/ADR-004-change-event-protocol-v0.md) and the Layer 4 service boundary. |
| `dogwood-compose` | guest (Kotlin/JavaScript) | `DogwoodApplier`, the change recorder, the lambda slot table, the recording stubs, the frame clock, and the [ADR-007](../adrs/layer-4/ADR-007-v1-wire-format-positional-json.md) encoder. |
| `dogwood-codegen` | build time | The generator. Parses the component surface with the Kotlin compiler's frontend and emits guest stubs, host dispatch, and the versioned dictionary. |
| `dogwood-host` | host (common Kotlin) | The binding dictionary, modifier reconstruction, the snapshot mirror, the decoder, the threading contract, the driver, and Layer 3 delivery. **This is where the architecture's claim lives:** one binding implementation, every platform. |
| `samples/slice-guest` | guest | The Phase 1 screen. Identical to the screen Phase 0 measured, so those numbers still describe it. |
| `samples/slice-desktop` | host | Compose Multiplatform desktop. The development loop, not a shipping target. |
| `samples/slice-android` | host | Android. The first shipping target. |

## Running it

Needs a Java Development Kit (JDK) 21 on `JAVA_HOME`.

The guest is **not** bundled into either host. It arrives over the network with a signed
manifest, so serve it first:

```bash
cd engine
./gradlew :samples:slice-guest:serveProductionWebpackZipline   # leave this running

# then, in another shell
./gradlew :samples:slice-desktop:run                           # desktop, localhost:8080

./gradlew :samples:slice-android:assembleDebug                 # Android, 10.0.2.2:8080
adb install -r samples/slice-android/build/outputs/apk/debug/slice-android-debug.apk
adb shell am start -n dev.dogwood.slice.android/.SliceActivity
```

### Signing

The manifest is signed with Ed25519 and the host will not load an unsigned or wrongly-signed
one. The key committed in `samples/slice-guest/build.gradle.kts` is a **throwaway development
key**, present so the sample builds for anyone who clones this; it signs nothing anyone should
trust. Use your own:

```bash
./gradlew generateZiplineManifestKeyPairEd25519
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline -PdogwoodSigningKey=<private-hex>
```

then put the public half in `TRUSTED_KEYS` in both sample hosts. That coupling is deliberate:
it makes key rotation an operation someone performs rather than something that drifts.

## The path a tap takes

```mermaid
sequenceDiagram
    participant User
    participant Compose as Host Compose
    participant Exp as DogwoodExperience
    participant Guest as Guest (QuickJS)

    User->>Compose: tap
    Compose->>Exp: EventSink.send(node, tag)
    Note over Exp: stamps the batch sequence<br/>the host had applied
    Exp->>Guest: sendEvent (Zipline thread)
    Note over Guest: lambda slot table resolves<br/>the captured closure
    Guest->>Guest: state mutates, scopes invalidate
    Guest->>Exp: requestFrame
    Exp->>Compose: withFrameNanos
    Compose->>Exp: frame(nanos)
    Exp->>Guest: frame (Zipline thread)
    Note over Guest: recomposition; the applier's<br/>onEndChanges flushes one batch
    Guest->>Exp: sendChanges (positional JSON)
    Note over Exp: decode on the Zipline thread,<br/>apply on the UI thread
    Exp->>Compose: snapshot state changes
    Compose->>User: only the readers recompose
```

### Diagram node definitions

- **Host Compose** — native Compose Multiplatform, which owns layout, drawing, accessibility
  and input. The guest has no Skia and no Compose UI.
- **`EventSink`** — how a binding reports that its widget produced an event. Bindings know
  nothing about Zipline.
- **`DogwoodExperience`** — the driver: the Zipline instance, the threading contract, the frame
  loop, and the event path.
- **Batch sequence stamp** — every event carries the sequence number of the last batch the host
  had *applied*, which is what lets the guest drop an event aimed at a node it has since
  removed. A double-tapped "Pay" button across a frame boundary is a correctness problem.
- **Lambda slot table** — maps `(node, event tag)` to the Kotlin closure captured during
  composition. Closures never cross the boundary; only tags do.
- **`requestFrame`** — the guest asks for a frame only when something awaits one, so an idle
  experience produces no traffic at all.
- **`onEndChanges`** — Compose calls this once at the end of applying a composition's changes,
  which is what makes "one batch per composition pass" true by construction.
- **Positional JSON** — the [ADR-007](../adrs/layer-4/ADR-007-v1-wire-format-positional-json.md)
  wire format, built as native JavaScript values so QuickJS's own `JSON.stringify` does the
  work. Twenty times faster than routing the same data through `kotlinx.serialization`.
- **Snapshot mirror** — the host's tree, whose every property is snapshot state, so applying a
  change invalidates exactly the composables that read it.

## Tests

```bash
./gradlew :dogwood-compose:jsNodeTest    # guest: composition and applier behaviour
./gradlew :dogwood-host:jvmTest          # host: decoding and the apply path
```

Both named Phase 1 gate conditions are covered, and they are covered where they actually live —
the guest tests decode the payload that crossed rather than inspecting the recorder, so a test
cannot pass while the bytes are wrong:

| Gate condition | Test |
| --- | --- |
| A state change three guest-defined wrapper layers deep crosses as a single property change | `WrapperScopingTest.stateChangeThreeWrappersDeepCrossesAsOnePropertyChange` |
| Node identity survives list reordering | `NodeIdentityTest.reorderingAListMovesNodesRatherThanRecreatingThem`, `HostTreeApplyTest.aMoveKeepsTheSameNodeInstances` |

The Layer 3 security boundary is tested in both directions, against the manifest the build
actually produces rather than against a fixture: the trusted key verifies, an untrusted key of
the same name is rejected, a swapped bytecode hash is rejected, and a redirected entry point is
rejected. One test records the *boundary* of what the signature covers — reformatting the
manifest is deliberately not tampering, because Zipline signs a canonical payload with the
`unsigned` block stripped, which is what lets a cached manifest record where it was fetched from
without invalidating itself.

Alongside them: an idle composition produces no traffic at all, removing rows purges their
closures depth-first, sequence numbers are monotonic, a node is attached only after its initial
properties are set, modifier chains keep their order, an unknown widget tag becomes a
placeholder so sibling indices survive a dictionary-version skew, and an absent property falls
back to the host default rather than to an empty value.

## Over-the-air update, demonstrated

The Android application package contains **zero guest bytes**. The whole user interface arrives
over the network. Edit the guest, rebuild it, restart the app without reinstalling, and the new
screen is there:

```bash
# with the server running
vim samples/slice-guest/src/jsMain/kotlin/dev/dogwood/slice/SliceScreen.kt
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline
adb shell am force-stop dev.dogwood.slice.android
adb shell am start -n dev.dogwood.slice.android/.SliceActivity
```

This is also where a caching default went wrong and was caught by trying it. `DogwoodDelivery`
first shipped with a twenty-four-hour manifest freshness window — an ordinary caching policy,
and completely wrong here: an update published to the server did not reach a restarted client at
all. The default is now [`REVALIDATE_EVERY_LAUNCH`](dogwood-host/src/ziplineMain/kotlin/dev/dogwood/host/Delivery.kt).
A longer window remains available, but it has to be chosen.

## Rendering strategy

Both candidate strategies are implemented behind the shared `WidgetView` interface — `HostTree`
(snapshot mirror) and `PlainTree` (imperative, Redwood's design) — and were measured against each
other. The snapshot mirror is kept; `PlainTree` stays in the tree as the measured alternative, so
the decision is reproducible rather than asserted. See
[Layer 5 ADR-007](../adrs/layer-5/ADR-007-keep-the-snapshot-mirror.md) and
[the results](../tools/phase0/results/render-strategy.md).

```bash
adb shell am start -n dev.dogwood.slice.android/.RenderBenchActivity
adb pull /sdcard/Android/data/dev.dogwood.slice.android/files/render-strategy.md
```

## Generated bindings

`surface/` holds the registered design system's declarations — ordinary `@Composable` signatures
with empty bodies. **Nothing compiles them.** `dogwood-codegen` reads them and emits, from one
parse, the guest stubs, the host dispatch layer and the versioned dictionary. Generated output
lands in `build/generated/dogwood/` and is not committed; generated code in version control is a
copy that drifts.

The generator emits the **bridge**, not the widget. Dispatch calls an implementation named by
convention — `Badge` in the surface calls `BadgeImpl` in `DesignSystemImpl.kt` — and that
implementation is ordinary Compose that knows nothing about tags, nodes or events. Nine of the
eleven registered components are bound this way with no hand-written dispatch on either side; the
two lazy containers remain hand-written, because their children must be composed inside the host's
`items` block, a slot shape the generator does not model.

**Tags are locked.** `surface/dogwood.designsystem.lock.json` is committed, and every build
compares against it. Additions update it; a renumbering or removal **fails the build**, naming the
component and both tags. This is mechanical rather than a review convention because reordering two
declarations is an innocent-looking edit, and a moved tag renders the *wrong* widget on a client
one version behind rather than failing to render.

```bash
./gradlew :dogwood-codegen:generateDesignSystem   # runs automatically before any compile
```

## The registered design system

Segment 1 is modelled on [Skyscanner Backpack](https://github.com/Skyscanner/backpack-android) —
public, Apache 2.0, built on atomic design, and shipping real Jetpack Compose components, which is
what makes the bindability audit a measurement rather than an exercise. The audit is
[Layer 5 ADR-008](../adrs/layer-5/ADR-008-design-system-audit-backpack.md); five of the eleven
components audited bind as declared.

Dogwood does **not** depend on the Backpack library — that would tie the host layer to Android,
and the host layer being common Kotlin is the property the architecture rests on. Its spacing and
corner-radius tokens are used as published; the colours are ours.

The sample screen exercises the result: real photographs loaded from a content delivery network by
Uniform Resource Locator, a horizontally scrolling carousel, filter chips, star ratings, badges,
struck-through prices, and a lazily rendered vertical list — all authored as ordinary Kotlin
Compose in the guest, none of it aware it is running inside an interpreter.

## Live code update

`DogwoodSession` watches the delivery flow and replaces the running experience when new code is
published — no restart, no reinstall. Guest state declared with `rememberSaveable` is captured
from the outgoing guest and handed to the incoming one.

Demonstrated on the emulator: with the app open and the chip row set to 4, publishing a new guest
produced `load #2: version 1.1.0, verified by dogwood-development, restored 24 saved state keys`,
the header text changed, and the chip row was still on 4.

What survives is exactly what the guest nominated with `rememberSaveable`, and nothing else. That
is a real limit rather than a temporary one: the replacement code may have a different composition
shape, so restoring anything the guest did not explicitly declare would be restoring state into a
tree that may no longer have a place for it.

Two sharp edges, both of which cost real debugging time here and are commented at the call sites:

- `rememberSaveable { mutableStateOf(x) }` needs an explicit `stateSaver`. Without it the call
  resolves to the generic overload and tries to save the state holder itself.
- Compose wraps state saves in a `MutableState` envelope so the restored value keeps its mutation
  policy, so a registry's `canBeSaved` has to look **inside** that envelope rather than reject it.

## What is not done

Named Phase 1 deliverables that this does **not** yet satisfy, so nobody mistakes a working
screen for a finished phase:

- **The design-system component audit has not happened.** The five segment-1 components here
  are plausible stand-ins, not the five highest-usage components of a real design system
  measured by call-site count.
- **`AsyncImage` draws a placeholder.** Real image loading is the resources subsystem, Phase 4.
- **Responsiveness is unmeasured on a real device.** Verified on an emulator only.
