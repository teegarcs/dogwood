# ADR-065: Two teams need two shells, and nothing else

**Date:** 2026-09-09
**Status:** Accepted

## 1. Context & Problem Statement

`DogwoodShell` takes a single `manifestUrl`. Its "several experiences" are entry points **within one
payload**, sharing that payload's cache, release guard, dispatcher and warm pool. Two teams shipping
on their own schedules do not fit inside that, so independent shipping means two shells.

The adoption audit recorded this as unexamined (B3): "Fine for one team; unexamined for an
organization." Two questions were open, and neither had an answer anyone had run:

1. What does the second shell actually cost?
2. The surface lock makes tags permanent. What happens when two branches each append a component,
   and what is the safe resolution?

The plan's instruction was explicit and is the reason this ADR says "no": *not new machinery until a
need is demonstrated*.

## 2. Decision

**No new engine machinery. Two shells is the shape, and what was missing was a sample, a
measurement, and a written-down merge workflow — which now exist.**

- `engine/samples/second-guest` is a second independently built, signed and versioned payload.
- `engine/samples/two-payloads` is one application hosting both, with everything that must be
  separate separate: cache directory, guard file, dispatcher, interpreter.
- `tools/two-payloads/measure.sh` runs it and prints the costs.
- [`docs/multi-team.md`](../../docs/multi-team.md) carries both halves, with a diagram.
- **There is no shared warm pool and none is proposed.**
- **Publishing rule, written rather than built:** payloads are published from the integration
  branch, never from a surface branch.

## 3. Rationale & Research

### The cost is disk, and it does not amortise

Measured on a development host with cold caches. A ratio between one shell and two on one machine,
which is a question a development host can answer honestly:

| | checkout (`slice-guest`) | search (`second-guest`) |
|---|---|---|
| disk cache after first load | 1,381,245 bytes | 1,150,555 bytes |
| time to first tree | 871 ms | 351 ms |

with `heapUsedBytes 14755936` for two live interpreters and two compositions.

**1.15 MB for a payload that draws three nodes** is the finding. Almost all of it is the Compose
runtime and the Kotlin standard library: the two payloads are separate Zipline containers with
separate module hashes and nothing is shared. A second team pays for the framework again, on disk.

Time to first tree tracks payload size rather than shell count — the two shells started at once and
neither queued behind the other, which is what separate dispatchers are for. Heap is unremarkable.

So the price of independent shipping is a copy of the runtime per payload. A product that finds that
unacceptable should ship fewer payloads with more entry points, which is exactly what a single
`manifestUrl` already provides — and is why it takes one.

### The lock conflicts loudly, which is what it is for

Run rather than described. Two branches, each appending a component, each at local tag 24 and
dictionary version 15. `git merge` conflicts in three files, and the lock's conflict names the
collision. The safe resolution is three steps, and the second is the one that matters: **reset the
lock to the merge base, not to either side**, then regenerate — the merge base is the last lock both
branches agreed on, so both additions append fresh in surface order.

Resolving by taking one side wholesale is refused, by name:

```
Exception in thread "main" java.lang.IllegalStateException: dictionary lock violated; tags are permanent:
  - FilterChip moved from tag 24 to 25
```

The failure this prevents does not crash. A payload built where tag 24 means `PaymentRow`, meeting a
client where it means `FilterChip`, renders the wrong component silently.

### The hazard the lock does not catch

**The version number merged cleanly, and it should not have.** Both branches set 15, so git took 15,
and for a moment two different dictionaries both called themselves version 15. After the merge this
resolves — tags become 24 and 25 and version 15 describes the merged surface consistently — but a
payload published from an unmerged surface branch declares a version that will mean something
different once the branch lands, and the pre-flight check ([ADR-061](../layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md))
will accept it, because the numbers match.

The rule is written rather than enforced because the enforcement point is a deployment pipeline —
something every organization has and no two of which look alike. A team wanting it mechanical can
compare a payload's `dogwood.segments` against the integration branch's lock at publish time.

### What building the sample found

The first run of `two-payloads` **refused one of its two payloads**:

```
[checkout/about] refused: the payload needs a dictionary this client does not have;
unknown segments: [acme.designsystem]
```

The host had not registered Acme's binding. Before ADR-061 the same mistake would have produced a
screen quietly missing three components. Caught the week the check landed, in a host written after
it, on a real configuration error.

## 4. Unstated Assumptions

- **That teams share one trust anchor.** The signing key is compiled into the client binary, so a
  second key would have to ship in the same application and rotate on the same schedule. Nothing
  prevents per-team keys — `DogwoodDelivery` takes a map — but the cost is two rotations, and this
  sample deliberately does not pay it.
- **That a development host's ratios carry.** Assumed for *ratios between two shells on one
  machine*, not for absolute numbers. No number here is a device number.
- **That disk is the binding constraint.** True for the measurement taken. A product with many
  payloads may find the constraint is the number of live interpreters instead, which this sample
  does not explore because two was the question asked.
- **That no shared warm pool is wanted.** A pool spanning two teams would have to choose between
  their screens on a signal neither team can see. If a product demonstrates a need, that is its own
  plan with its own ADR — which is the rule this ADR is applying, not setting aside.

## 5. Updated Documents

- [`docs/multi-team.md`](../../docs/multi-team.md)
- [Adoption audit](../../plans/adoption-audit.md) — B3 closed
- [Engineering backlog](../../plans/engineering-backlog.md) — S4 marked done
- [Checks](../../docs/checks.md)
