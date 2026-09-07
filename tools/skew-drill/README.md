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

## The same drill on iOS and the web

Until 2026-09-06 this page described one client. The rules live in shared code, but *reaching* them
depends on each host's own composition — what it provides, what it wires, what it registers — so a
rule that passes everywhere in unit tests can coexist with behaviour that works in one place. Two
sibling scripts put the other two clients in the same condition, with the same five steps, the same
claim identifiers, and the same refusal to read the outcome off the host's own log:

| | Script | How the outcome is read |
| --- | --- | --- |
| Android | `run.sh` | `uiautomator dump`, from outside the process |
| iOS | `run-ios.sh` | the accessibility tree, walked in-process by `SkewDrill.kt` — `xcrun simctl` cannot dump a hierarchy |
| Web | `run-web.sh` | `Accessibility.getFullAXTree` plus each node's box model, over the DevTools protocol |

The web one is the most literal expression of the two-build shape: `app.js` and `guest-kotlin.js`
sit in one directory, so it rebuilds one file and leaves the other alone. It hashes `app.js` before
and after and **fails if it changed** — a host rebuilt alongside the guest is a drill that quietly
tests nothing.

It also runs a fourth claim the mobile clients cannot make. The web client checks the payload's
declared dictionary versions *before* it creates the Worker, so the drill runs both halves:
undeclared skew must be contained at render time, and declared skew must be refused outright
(**B3**, against a genuinely newer payload rather than a hand-written manifest). The mobile hosts
have no such pre-flight check — their Zipline manifests carry no segment versions — so on those
clients the render-time rules are the only line of containment there is.

### What the ports found

Both, on their first run, in code no test covers. Recorded in
[ADR-052](../../adrs/layer-5/ADR-052-the-skew-drill-on-every-client.md).

**The web host provided none of the composition locals its bindings read.** `A4` passed — the
control carrying an unreadable affordance-bearing property was correctly withheld — and
`A4-reported` failed:

```
CONF A4 PASS -- the button is absent from the accessibility tree
CONF A4-reported FAIL -- SkewReport(widgets=[16777233] )
```

`DogwoodWebExperience.Content()` rendered `RenderChildren` directly rather than through
`DogwoodTree`, and every local involved has a default — so the page rendered perfectly and threw
away everything its bindings recorded. Only the unknown *widget tag* survived, because `HostTree`
writes that one straight onto the tree rather than through the local. Two more went with it: the
expression cache was keyed to nothing rather than to a guest, and live-state mirrors had no
`LocalGuestGeneration`.

**iOS never registered the product design system.** The first run reported four unknown widget tags
where the drill had introduced one:

```
widgets=[16777233, 33554435, 33554433, 33554434]
```

The last three are segment 2, tags 1 to 3 — Acme's components, inert placeholders on the one client
of four that neither depended on `samples/product-design-system` nor called `DogwoodRegistry.register`.
That module's build file already carried the rule, written when the web hit the identical problem:
*a product's design system has to target every platform its hosts run on.* Stated, and not applied
to the platform added after it.

Both clients are green now, and both fixes are in host integration code rather than in the engine:

```
CONF RESULT client=ios passed=5 failed=0 skipped=0
CONF RESULT client=web passed=6 failed=0 skipped=0
```

## Running it

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :samples:slice-guest:serveProductionWebpackZipline   # in another shell

tools/skew-drill/run.sh        # Android: needs an attached device or emulator
tools/skew-drill/run-ios.sh    # iOS: needs a booted simulator; turns VoiceOver on itself
tools/skew-drill/run-web.sh    # Web: needs Chrome, and no device or payload server at all
```

`tools/conformance/run-all.sh` runs all three. Each refuses rather than fails when its prerequisite
is missing, so a partial run reports what it could not do instead of reporting green.

**VoiceOver has to be on for the iOS run**, because Compose Multiplatform builds its accessibility
tree only while an assistive technology is active — with it off, the walk finds the rendering view
and nothing under it. `run-ios.sh` turns it on and `SkewDrill.kt` refuses rather than reports if it
is still off, so a forgotten switch cannot read as a passing run. The web drill's equivalent is
Chrome's `--force-renderer-accessibility`.

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
