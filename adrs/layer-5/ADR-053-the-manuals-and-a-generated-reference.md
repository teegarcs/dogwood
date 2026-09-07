# ADR-053: The manuals, and a reference nothing can contradict

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

`plans/production-readiness.md` §4b named four documents that did not exist and one that did:

| Document | For whom |
|---|---|
| Getting started | somebody adding Dogwood to an existing application |
| Authoring guide | somebody writing screens |
| Operations guide | whoever is on call |
| API reference | everyone |

The only user-facing prose was `developer-experience.md`, written to persuade somebody deciding
whether to adopt — and **two of its statements were false for seven phases.** It declared the system
unbuilt and described a build-time dictionary check that does not exist. Nothing caught either,
because no check reads prose. That is the risk this decision has to answer, not merely the absence.

## 2. Decision

**Write three manuals by hand and generate the fourth.**

- [`docs/getting-started.md`](../../docs/getting-started.md) — the two builds, the host in outline,
  a product's own segment, the payload, signing and serving.
- [`docs/authoring.md`](../../docs/authoring.md) — what payload code may and may not do, **each rule
  with the reason it exists**.
- [`docs/operating.md`](../../docs/operating.md) — what the device does without an operator, how to
  stop a release, how to see skew, and §6: what this does *not* do.
- [`docs/api/`](../../docs/api/) — **emitted by the generator** from the same parse as the bindings,
  via `--docs-out` and the plugin's `referenceFile`.

## 3. Rationale & Research

**The reference is generated because a hand-written one is a fifth thing that can disagree with the
surface.** The generator already produces four artifacts from one parse — guest stubs, host
bindings, the dictionary, the segment-version vector — precisely so the two ends of the boundary
cannot drift. A hand-maintained component page would reintroduce the drift the whole design exists
to remove, in the document a reader trusts most. So [`Docs.kt`](../../engine/dogwood-codegen/src/main/kotlin/dev/dogwood/codegen/Docs.kt)
renders the parsed model: names, tags, kinds, defaults, affordances, declared ranges.

**It is written into the source tree and committed, exactly as the lock is.** A reference generated
into `build/` is a reference nobody opens. Both generate tasks declare it as an output, so deleting
it regenerates it rather than leaving the task up to date with a missing file.

**It carries no prose about what a component is *for*, and says so.** The parser does not read
documentation comments; a generator that invented that prose would be worse than one that omits it.

**A product gets one with a single line.** `referenceFile.set("docs/components.md")` in the
`dogwood` block. `samples-standalone/umbra` sets it, and `tools/standalone-check/run.sh` asserts the
file **contains a widget tag** rather than merely existing — an empty file satisfies `-f`, and the
number a reader comes for is the tag. Verified outside this repository:

```
| [`UmbraBanner`](#umbrabanner) | 1 | 50331649 |  |
| [`UmbraStepper`](#umbrastepper) | 2 | 50331650 | yes |
```

**The operations guide is written around what is built, and names what is not.** Its §6 lists the
absences — nothing resumes a previous payload automatically, there is no staged rollout, refusals
are not reported unless a host wires them, the performance budgets are ungraded. A runbook that
describes a capability nobody built is worse than no runbook, because it is consulted during an
incident.

**Each rule in the authoring guide carries its reason**, because a rule whose reason is missing gets
worked around. "No `animateFloatAsState`" invites a workaround; "per-frame state ticks the boundary
every frame it animates, so declare a target and the host runs the frames" does not.

## 4. Unstated Assumptions

- **Nothing checks the three hand-written manuals for truth.** That is the same exposure
  `developer-experience.md` demonstrated, reduced but not removed: they were written against the
  code and their API examples were corrected against it — `animateScrollTo` rather than `animateTo`,
  `Formats.plural(...)` rather than a plural argument that does not exist — but a later change can
  falsify them silently. The generated reference is the part that cannot go stale.
- **The published coordinates in getting-started are `0.1.0`**, which is what
  [ADR-047](ADR-047-the-generator-ships-as-a-plugin.md) publishes and what Umbra resolves. They are
  not deployed to any public repository; that remains an owner's decision.
- **`docs/api/` covers the two segments this repository builds.** The layout segment (0) is
  hand-written and has no surface to generate from, so it is absent — an omission, not a decision.

## 5. Updated Documents

- [`docs/getting-started.md`](../../docs/getting-started.md), [`docs/authoring.md`](../../docs/authoring.md),
  [`docs/operating.md`](../../docs/operating.md), [`docs/api/`](../../docs/api/) — new.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — §4b's table and item 10.
- [`README.md`](../../README.md) — a `docs/` row in the repository layout.
- [`engine/dogwood-codegen/`](../../engine/dogwood-codegen/) — `Docs.kt`, the `--docs-out` option,
  and the plugin's `referenceFile`.
- [`tools/standalone-check/run.sh`](../../tools/standalone-check/run.sh) — asserts a product's
  reference is generated and carries tags.
