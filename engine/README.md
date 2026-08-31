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
| `dogwood-host` | host (common Kotlin) | The binding dictionary, modifier reconstruction, the snapshot mirror, the decoder, the threading contract, and the driver. **This is where the architecture's claim lives:** one binding implementation, every platform. |
| `samples/slice-guest` | guest | The Phase 1 screen. Identical to the screen Phase 0 measured, so those numbers still describe it. |
| `samples/slice-desktop` | host | Compose Multiplatform desktop. The development loop, not a shipping target. |
| `samples/slice-android` | host | Android. The first shipping target. |

## Running it

Needs a Java Development Kit (JDK) 21 on `JAVA_HOME`.

```bash
cd engine
./gradlew :samples:slice-desktop:run                    # desktop
./gradlew :samples:slice-android:assembleDebug          # Android
adb install -r samples/slice-android/build/outputs/apk/debug/slice-android-debug.apk
adb shell am start -n dev.dogwood.slice.android/.SliceActivity
```

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

## What is not done

Named Phase 1 deliverables that this does **not** yet satisfy, so nobody mistakes a working
screen for a finished phase:

- **Delivery is not Layer 3.** The guest is loaded from local files and Android assets. No
  signed manifest, no Ed25519 verification, no disk cache, no `ZiplineLoader`. Roadmap step 9.
- **The host rendering strategy has not been compared.** The snapshot mirror is implemented;
  the imperative applier that Redwood uses has not been built, so the apply-to-pixel comparison
  at batch sizes 1 / 10 / 100 / 1000 that roadmap step 7 calls for has not been run.
- **The design-system component audit has not happened.** The five segment-1 components here
  are plausible stand-ins, not the five highest-usage components of a real design system
  measured by call-site count.
- **`AsyncImage` draws a placeholder.** Real image loading is the resources subsystem, Phase 4.
- **Two gate conditions are unverified**: that node identity survives list reordering, and the
  wrapper-scoping test — a state change three guest-defined wrapper layers deep crossing as a
  single property change. `key()` and the applier's move handling are written for both; neither
  has a test.
- **Responsiveness is unmeasured on a real device.** Verified on an emulator only.
