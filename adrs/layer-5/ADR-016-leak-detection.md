# ADR-016: Leak Detection — Adopted, Aimed, and It Immediately Found One

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4: "**Leak detection.** Adopt `redwood-leak-detector`. **Before iOS, not
after** — cross-language reference cycles span Kotlin/Native garbage collection and Swift reference
counting."

The sequencing argument is the whole point. A cycle that spans a garbage-collected language and a
reference-counted one is miserable to find once a platform is shipping, and the instrument has to
be in place and *trusted* before that happens. But there is no iOS host yet, so the question this
record has to answer is narrower: what can leak detection do for Dogwood **today**, on the two
hosts that exist?

The answer turned out to be more than expected.

## 2. Decision

**Adopt `app.cash.redwood:redwood-leak-detector` 0.18.0 (Apache 2.0), behind one Dogwood type.**

It publishes Java Virtual Machine, JavaScript, WebAssembly and iOS targets; an Android consumer
resolves the Java Virtual Machine variant through the Kotlin plugin's platform-type compatibility.
It is a time-based detector: a watched reference that survives repeated collections past a
threshold is reported.

It is **wrapped rather than exposed**. The library annotates its interface `@RedwoodLeakApi`,
"unstable and for Redwood internal use only", and Redwood is a discontinued project. Putting an
explicitly-internal third-party type into Dogwood's public signatures would make every host that
watches for leaks depend on it directly. So `DogwoodLeakWatcher` is a one-method interface,
`DogwoodLeakDetector` wraps the implementation, and the opt-in lives in a single file that can be
replaced without touching a caller.

**Two things are watched, and they are the two places this architecture can leak.**

1. **A detached subtree**, in `HostTree.purge`. Removing children must forget them depth first, or
   a feed that creates and destroys ten thousand rows retains ten thousand nodes and every property
   map in them.
2. **A replaced guest generation**, in `DogwoodSession`. This is the one that matters and it is
   peculiar to Dogwood. A retained `DogwoodExperience` is not one stale object: it holds the
   Zipline instance, and through it an entire QuickJS heap with a whole composition inside. A code
   update while a screen is live is the *normal* case here, so leaking a generation per publish
   means a long-lived screen accumulating interpreters.

**The guest's own heap is deliberately not watched.** Its retention hazard — an event closure
outliving the node that registered it — is structural rather than collectible. A test that counts
`lambdaSlotCount` after a removal is a deterministic assertion where a garbage-collection probe
would be a flaky one, and that test already exists.

**Detection is off by default.** `DogwoodLeakWatcher.None` is the default for `HostTree`,
`DogwoodExperience` and `DogwoodSession`, because watching costs a weak reference per detached node
and a periodic collection. The Android sample turns it on, because it is a development slice and
this is the thing worth watching in one.

## 3. Rationale & Research

**It found a real defect on its first run, and the memory was the smaller half of it.**

Six code updates published in a row against the Android emulator produced exactly one report, every
time, deterministically:

```
W DogwoodSlice: LEAK: guest generation #1, replaced by a code update
```

Generations two through six were collected within three seconds of being replaced. Generation one
was never collected. A canary — a bare `Any()` watched at the same call site, in the same
iteration — was *not* reported, which ruled out the coroutine frame and the test itself and said
the retention was in the object graph.

The cause is specific to this architecture and would have been very hard to reason to:

> A code update replaces the guest and therefore the whole host tree. The replacement tree hands
> out the same node identifiers from one, so `key(node.id)` matches the same composition groups and
> every `remember` in a binding is preserved — which is exactly what makes a code update feel
> seamless rather than like a reload. The consequence is that a `LaunchedEffect` keyed on anything
> **tree-stable** is not restarted, and goes on using whatever it captured in the composition it
> was launched in.

The lazy list's viewport reporter is keyed on the node identifier and the host's own
`LazyListState`, neither of which changes across a code update. It had captured the `EventSink`
from its first composition, and that sink captures the `DogwoodExperience`.

So the leak was the symptom. **The defect was that viewport reports after a code update were being
delivered to the previous, closed guest.** No user-visible error, no exception, no log line: the
reports simply went to a guest nobody was looking at, while the current one was told nothing about
where its list was.

The fix is `rememberUpdatedState` for both the node and the sink, so the long-lived effect always
reads the current ones. After it, the same six-update run reports **nothing** at a three-second
threshold.

**This generalises into a rule for bindings, and therefore for the generator**, alongside the
`key`-must-be-outermost rule from [ADR-015](ADR-015-node-identity-and-reuse.md): a binding's
long-lived effect must not capture the event sink or a node directly, because the composition
outlives the guest that filled it.

### What is tested, and how the tests were made honest

- **A detached subtree becomes collectable**, with a negative control showing an attached node does
  not. Deterministic, using weak references and forced collection rather than the detector.
- **A reference that survives is reported**, so the instrument is known to work in this codebase.
- **A detached node is not reported.** This one began as a bare `detector.watch(Any(), …)` and
  reported a leak on every run — the object was still live in a stack slot for the duration of the
  test, so the detector was right and the test was wrong. Watching something that becomes
  unreachable *the way the production code makes things unreachable* is both a truer test and a
  collectable one. A detector that reports healthy code gets switched off, and then it is not
  detecting anything.
- **A regression test for the stale capture**, in the host composition harness from ADR-015: a tree
  is swapped for a replacement with the same identifiers, and the viewport report must reach the
  new sink and not the old. Verified non-vacuous by reverting the fix, at which point it fails with
  "the replacement guest received nothing, so the reporter is still talking to the guest that was
  closed."

## 4. Unstated Assumptions

- **`System.gc()` is a request.** The detector's threshold is a heuristic, not a proof: a reference
  reported as leaked was *not collected within the threshold*, which is not the same statement.
  The canary and the deterministic weak-reference tests exist because of that gap.
- **The dependency is on a discontinued project.** Redwood's final release is public and the
  artifact will remain on Maven Central, but nothing will be fixed in it. `DogwoodLeakWatcher` is
  the insurance: the implementation is one file.
- **No Android artifact is published.** The Java Virtual Machine variant resolves for an Android
  consumer today through the Kotlin plugin's compatibility rule. If that rule changed, the
  dependency would need an explicit `jvm` fallback or a vendored copy.
- **The iOS claim is untested here, because there is no iOS host.** What this record establishes is
  that the instrument is adopted, wired to the right places, and known to work — which is what
  "before iOS, not after" asks for. The cross-language cycle hunt itself belongs to Phase 6.
- **Only one long-lived effect exists in the bindings today.** The rule above is stated because the
  generator will emit more of them, not because more exist.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the memory-ownership section gains the
  two watch points and the stale-capture rule.
- [`roadmap.md`](../../roadmap.md) — Phase 4's leak-detection row.
- [`adrs/README.md`](../README.md) — index entry.
