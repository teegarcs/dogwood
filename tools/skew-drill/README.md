# The skew containment drill

Section 6 of the [technical specification](../../high-level-tech-spec-final.md) makes three
requirements of a client meeting a payload built against a **newer** dictionary than its own. Each
was implemented and unit-tested; none had ever been run end to end, against a real client, from a
real payload. This is that run.

## Why it needs two builds

Dogwood's host and guest are generated from one surface, so a normal build cannot be skewed — both
halves always agree. The drill exploits the fact that the two are *delivered* differently: the host
bindings are compiled into the Android application, and the guest payload is fetched over the
network at run time. So:

1. Build and install the Android application at dictionary **version 7**.
2. Change the surface, regenerate to **version 8**, and rebuild **only the guest payload**.
3. Serve it to the already-installed version 7 client, which is **not** reinstalled.

The version 8 surface added three things, one per requirement:

| Added | Where | Requirement it exercises |
| --- | --- | --- |
| `Callout`, a new component at local tag 15 | new | unknown widget tag |
| `Badge.subtitle`, property tag 3 | a widget with **no** affordance | unknown property |
| `PrimaryButton.tone`, property tag 3 | a widget that **owns** an affordance (`@Affordance enabled`) | safety-relevant fallback |

## Results

Run on an Android emulator. The guest rendered, in order: a `Text`, the unknown `Callout`, a
second `Text`, the `Badge` carrying an unknown property, and a `PrimaryButton` carrying one.

**1. An unknown widget becomes a placeholder, and index arithmetic survives.** ✅

```
SKEW-BEFORE   y =  48 .. 120
              (gap 120 .. 168 — the placeholder, occupying its slot)
SKEW-AFTER    y = 168
SKEW-BADGE    y = 276
```

The sibling *after* the unknown node kept its position. Had the create been skipped rather than
placeheld, every later index in that slot would have shifted by one.

**2. An unknown property on a widget with no affordance is ignored, and the widget still renders.**
✅ `SKEW-BADGE` drew normally while carrying property tag 3, which this client has never heard of.

**3. An unknown property on a widget that owns an affordance withholds the widget.** ✅ `SKEW-PAY`
was absent from the rendered tree entirely — confirmed absent rather than merely off-screen, in a
52-node dump. This is [ADR-031](../../adrs/layer-5/ADR-031-safety-relevant-parameters.md)'s rule
firing end to end for the first time: the client could not read what the payload was saying about
that button, and one of the things it might have been saying is "this is disabled".

**No crash, at any point.**

**4. And it was reported**, which is the part that turned out not to be true when the drill started:

```
skew: SkewReport(widgets=[16777231] withheld=[16777217])
```

`16777231` is `(1 shl 24) or 15` — the `Callout` tag. `16777217` is `(1 shl 24) or 1` — the
withheld `PrimaryButton`.

## Two findings

**No sample read the skew report.** Containment worked and was invisible. A report nobody surfaces
teaches no team that its payloads have moved ahead of its devices, which is the entire purpose of
`SkewReport`. The Path B sample now shows it.

**The report must be sampled, not observed.** `withheld=` was missing from the first display and
appeared only after an unrelated tab switch forced a redraw. `SkewReport` is plain sets rather than
snapshot state, deliberately: most of it is written *during composition* — an unknown colour token
by the binding that could not resolve it, a withheld widget by the guard that declined to draw it —
and writing snapshot state there is not allowed. So a composable reading it directly sees whatever
was there when that pass began. The sample polls it instead, and `Skew.kt` now says so.

## Running it

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :samples:slice-guest:serveProductionWebpackZipline   # in another shell
tools/skew-drill/run.sh
```

It was a manual procedure until 2026-09-05 — edit the surface, bump the version, rebuild the guest,
remember not to reinstall the application — and the platform review's observation about it was
correct: **a drill that needs a person to edit source before it runs is a drill that runs
approximately never.** Everything it asked for was mechanical, so the script does it, and restores
the surface on every exit path including a failure or an interrupt. A permanently skewed surface
would be a permanently failing lock, which is why the restore is a `trap` rather than a final step.

Output is the `CONF` grammar, so `tools/conformance/aggregate.py` reads this run like any other:
claims `A2`, `A3` and `A4`, plus a control and a report check.

### Two things the automation had to learn

**Property tags are append-only, and the lock says so.** The first version of `skew.py` inserted the
new properties at the front of each parameter list, and the generator refused: *"PrimaryButton.enabled
moved from property tag 2 to 3. Append to the surface instead of reordering or removing."* That is
the dictionary lock doing exactly its job, caught by a drill that exists to test the rules around
it. The additions go at the end now.

**`grep -q` inside `set -o pipefail` reports a success as a failure.** It exits on the first match,
which sends `SIGPIPE` to whatever feeds it, which `pipefail` then reports as a failed pipeline — so
the check for "has the skewed payload arrived?" kept saying no when the file plainly contained the
marker, in two different spellings before the pipe was removed altogether. Shell plumbing reporting
a product failure is the same trap the `--console-pty` carriage returns were, and worth recognising
on sight.

### Negative control

Making `unknownProperties` return nothing — so no widget is ever withheld — turns `A4` and
`A4-reported` red while `A3` stays green:

```
CONF A4 FAIL -- the button rendered while carrying an unreadable affordance-bearing property
CONF A4-reported FAIL -- SkewReport(widgets=[16777231] )
```

That `A3` keeps passing is the useful part: the two rules are independent, and the drill can tell
them apart.
