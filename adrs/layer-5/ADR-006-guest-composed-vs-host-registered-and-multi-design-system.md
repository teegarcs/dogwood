# ADR-006: Guest-Composed vs Host-Registered Components, Multi-Design-System Registration, and the Design-System-First Adoption Path

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

Three questions arose while planning company adoption, and none had an answer written anywhere in the specifications:

1. **Where is the line between "bridged" and "just Compose"?** A feature team owning three screens will define a reusable `@Composable` (say, `OrderSummaryCard`) that wraps five other components — some generated stubs, some design-system components. Does that wrapper need a dictionary entry, a host release, any registration at all?
2. **Can host-registered components carry more than one design system?** The company has one design system today; others exist and more will appear. Registration must be a repeatable, low-friction operation, not a one-time integration.
3. **Does the adoption path have to climb through the full generated Material tier first?** The roadmap sequenced generator → Modifier → deferred expressions before any product surface. For a company with a curated design system, that ordering buries the fastest path to a shippable screen.

## 2. Decision

### 2.1 The line: a component is bridged if and only if its implementation must live host-side

In Compose, a node exists only when a composable *emits* one. In the guest, the only emitters are Dogwood's stubs — the generated tier and the registered components. **Every other `@Composable` a developer writes is a plain function**: it executes inside the guest composition, and only the leaf stub calls it makes produce protocol nodes. A guest-defined wrapper therefore:

- ships inside the payload and updates Over-The-Air (OTA) with it,
- needs **no dictionary entry, no tag, no registration, and no host release**,
- gets full Compose semantics — parameters, `remember` state, and its own recomposition scope (a state change inside the wrapper crosses the boundary as the minimal property diff, not a re-emit of the wrapper),
- has **no version-skew surface at all**, because it versions atomically with the payload.

A component justifies a dictionary entry only when its implementation needs powers the sandbox lacks: real rendering behaviour (canvas, charts, custom layout, internally-owned animation), platform integration (video, maps, camera, native text input), asset loading, or a deliberate choice to pin its behaviour to the app release. **The dictionary is the vocabulary; guest code is prose written in it.** The dictionary should essentially never grow because a feature team composed something.

**The either-side lever.** A purely compositional component can live on either side, and the choice is a real design decision: host-registered means one node crosses and internals render natively, but changes require a release and the signature joins the forever-backward-compatible dictionary surface; guest-compiled (publishing the component library as a Kotlin Multiplatform module that also compiles against the Dogwood stubs) means its internals cross as several nodes, but it updates OTA and adds zero dictionary surface. **Default rule: behavioural components host-side; compositional components guest-side.**

### 2.2 Registration is multi-tenant by design

The generator accepts **any number of registered modules**, not one:

- **Dictionary segments.** The dictionary is partitioned into namespaced segments — the generated androidx tier plus one segment per registered module (`dogwood.material3`, `acme.designsystem`, `acme.checkout-kit`, …). Tag spaces are partitioned by segment so registrations can never collide, and each segment carries its own version, so one design system can evolve without re-versioning the others.
- **Registration is build configuration, not integration work.** Registering a design system is a Gradle declaration — point the Dogwood plugin at the module — after which the same pipeline runs for it as for the androidx tier: guest stubs, host bindings, dictionary segment, and the Layer 1 checker all come from the one parsed model. Adding a second or fifth design system is the same one-line operation as the first.
- **The bindability rule applies to registered signatures unchanged**: parameters must be primitives, value classes, deferred-expressible objects, composition-time slots, or discrete events. The build fails registration for a signature that violates it, with an error naming the offending parameter. Curated design-system signatures satisfy this far more often than raw Material does — which is precisely why registration is high-leverage.
- **Per-segment skew.** The manifest records the version of every segment the payload was compiled against; [Layer 3](../../specs/layer-3-delivery.md)'s dictionary check and the containment rules of overview section 6 (binding on [Layer 5](../../specs/layer-5-host.md)) operate per segment. Backward compatibility within a segment follows the same rules as the androidx tier: additive optional parameters are safe; renames and removals are breaking. Teams that must stay backward-compatible anyway (defaults and overloads) already practice the required discipline.

### 2.3 Registered components absorb bespoke subsystems

What a registered component does *internally* never crosses the boundary. A design-system `PrimaryButton` that owns its press animation, loading spinner, and analytics needs none of the animation subsystem to deliver them; a registered `AsyncImage(url, placeholder)` delivers images without the general resources subsystem; a registered chart component delivers what the `Canvas` exclusion forbids. The general subsystems in [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) remain the long-term answer for the *generated* tier; registration is the short-term answer for a company with a curated catalog — and it converts several "blocks the first screen" items into "covered by the design system on day one."

### 2.4 The design-system-first adoption path is the recommended company sequencing

The first shippable surface is built from **registered design-system components plus the layout primitives**, hand-written in the Phase 1 slice and generated in a v1 generator that targets *registered modules and `foundation-layout` only*. The full generated Material tier — with its defaults-expression complexity — becomes generator v2 rather than a prerequisite. This mirrors the architecture Redwood actually shipped (app-defined widget schemas) while preserving Dogwood's differentiator as the upgrade path. The [roadmap](../../roadmap.md) records this as the recommended sequencing.

## 3. Rationale & Research

**The emit mechanism is Compose's own, not a Dogwood invention.** Only functions that reach [`ComposeNode`](https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#ComposeNode(kotlin.Function0,kotlin.Function1,kotlin.Function1)) materialise nodes — Redwood's guest side is built on exactly this call (see [`redwood-compose`](https://github.com/cashapp/redwood/tree/trunk/redwood-compose/src/commonMain/kotlin/app/cash/redwood/compose), whose generated widget functions wrap `ComposeNode` over `NodeApplier`) — arbitrary guest composables composed freely over a fixed widget schema, with only schema widgets crossing the protocol. Dogwood inherits the behaviour by using the real Compose runtime ([Layer 4](../../specs/layer-4-sandbox.md)).

**Shared guest libraries do not force payload duplication.** Zipline manifests are multi-module with dependency ordering, and [Layer 2](../../specs/layer-2-compiler.md) already preserves module structure so unchanged modules cache across releases. A `feature-common` guest module ships once and is cached across experiences.

**One verification obligation, not assumed:** recomposition scoping through deep guest wrapper layers must be shown to produce minimal diffs (a state change three wrappers deep crossing as one `PropertyChange`). It should hold — it is the real runtime — but it is now an explicit test in Layer 4's roadmap, not an assumption.

## 4. Unstated Assumptions

- **Assumes design-system modules are parseable by the same frontend pipeline as Compose itself.** They are first-party Kotlin, so this is *easier* than the androidx tier (no metalava, sources available), but signatures using exotic types will surface bindability errors that teams must fix at registration time.
- **Assumes design-system teams accept dictionary discipline.** An exposed signature becomes a versioned contract; additive-optional evolution is the safe path. The company's existing backward-compatibility practice suggests this is acceptable.
- **Assumes tag-space partitioning is sized generously** (segment identifier + local tag), so no registration ever renumbers another segment.
- **Assumes a registered component's guest stub and host implementation stay in step via the segment version**, exactly as the androidx tier does via the dictionary version. Nothing new is invented; the mechanism is reused per segment.

## 5. Updated Documents

- [specs/layer-5-host.md](../../specs/layer-5-host.md) — subsystem 9 expanded: segmented dictionary, multi-design-system registration, the bridged-vs-composed rule
- [specs/layer-1-authoring.md](../../specs/layer-1-authoring.md) — the authoring model: guest composables are plain Compose; shared guest modules
- [specs/layer-2-compiler.md](../../specs/layer-2-compiler.md) — shared guest library modules ride the multi-module manifest cache
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — wrapper-recomposition minimal-diff test added to the roadmap
- [roadmap.md](../../roadmap.md) — design-system-first sequencing and the revised platform order
- [developer-experience.md](../../developer-experience.md) — the "Your Own Composables Need Nothing — the Week-One Question, Answered" section
- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — section 1 framing and target-platform phasing
