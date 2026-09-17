# The conformance plan: one capability list, four clients

> **Status: the rollout is complete, 2026-09-05.** All nine steps are done, across pull requests
> #21–#35. Every claim group has real-client evidence on the platforms it applies to; the matrix is
> generated from real runs and gates in two places. What is carried rather than closed is listed at
> the end of Part 6.

## Part 0 — Why this exists

Every drill in `tools/` was built to answer a problem that had just bitten us, on the one client
where it bit. That produced good instruments and a lopsided map:

| Drill | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| Accessibility | — | ✅ | — | — |
| Skew containment | ✅ | — | — | — |
| Hostile property values | ✅ | ✅ | — | — |
| Leak soak | — | ✅ | — | — |
| Phase 0 performance | ✅ | ✅ | ✅ | — |
| Page weight, first frame | n/a | n/a | n/a | ✅ |

Nothing there is wrong. What is missing is the thing that makes it a *system*: a statement of what
this architecture promises, written once, against which every client is graded — so that building
the fifth client is a matter of working down a list rather than rediscovering, one incident at a
time, what the fourth one already knew.

The failure this prevents is specific and this project has already met it four times. Evidence
machinery gets built, and nothing consumes it: the dead `skew` variable, the tautological `--gufa`
gate, an accessibility probe with no caller, an unsoaked leak suite. A per-client drill with no
catalogue above it is the same shape one level up — an instrument that answers a question nobody
asked twice.

### Three decisions taken before writing this, so the empty cells mean something

1. **Desktop is a development loop, not a shipping target** (roadmap Phase 1 step 2, reaffirmed
   2026-09-04). It is graded on **correctness only** — protocol, skew, hostile values, host
   resolution. Accessibility, performance budgets and platform-integration claims are marked *out
   of scope* for desktop, with that reason, rather than left as gaps somebody will feel guilty
   about. If desktop is ever promoted, the exempt cells are exactly the work.
2. **Design-system parity on web is committed.** The web host binds five layout widgets and one
   segment today. The design-system rows are therefore recorded as **gaps with owners**, not as
   "not applicable" — the matrix is supposed to apply pressure there.
3. **Conformance gates.** A red drill blocks the merge. This follows the house rule that a gate
   which cannot fail is not a gate, and it is the answer this project's own history argues for: a
   report nobody blocks on is a report nobody reads. The cost is flake tolerance, and Part 4 says
   what is done about it.

## Part 1 — The architecture

The instinct is to write the tests once and run them everywhere. **That is not available here, and
pretending otherwise is how this goes wrong.** The thing under test on the accessibility rows is
UIKit's `UIAccessibility` on iOS, Android's `AccessibilityNodeInfo`, and the DOM's accessibility
tree on web. There is no shared surface. The same is true of storage, of the network stack, and of
the garbage collector.

So the sharing happens one level up. Three things are shared; the test bodies are not.

**1. The claim.** Every promise this architecture makes is a numbered entry in Part 2, phrased as
something observable from outside. `D4` means the same thing on every client, and is the unit the
matrix is scored in.

**2. The report grammar.** Every drill, on every client, in every language, emits the same lines:

```
CONF <id> PASS|FAIL|SKIP <detail>
CONF RESULT client=<android|ios|desktop|web> passed=<n> failed=<n> skipped=<n>
```

That is the whole contract, and it is deliberately a text format rather than a library, because the
four clients are Kotlin/JVM, Kotlin/Native, Kotlin/Wasm and a browser — and a shared *library* would
have to exist in all four, which is precisely the constraint that made the test bodies unshareable
in the first place. A text grammar has no such problem. One aggregator reads all four runs and
writes the matrix.

A `SKIP` must carry a reason and is not a pass. Skips are counted in the matrix, so "we never ran
it" cannot masquerade as "it works".

**There is deliberately no verdict for "fails, but not our fault."** One was added and removed
inside a day, and the reason it was removed is the argument against it. The web drill reported that
a control could not be reached by keyboard; that looked like an upstream limitation, so it got an
escape hatch that recorded the failure without blocking. The failure was not real — the check had
read a `focusable` flag off the accessibility node instead of pressing Tab, and Compose routes
keyboard focus through the canvas rather than through per-element `tabindex`. **The escape hatch
legitimised a wrong conclusion within an hour of existing**, which is exactly what such a mechanism
is for and exactly why this catalogue does not have one. A claim passes, does not apply, or fails.

**3. The tier.** Each claim is assigned the cheapest instrument that can honestly settle it:

| Tier | Instrument | Covers | Runs |
|---|---|---|---|
| **S — shared** | `commonTest` in `dogwood-wire` / `dogwood-host` | Claims about code every client actually shares | Every build, gates the merge |
| **C — client drill** | A launch flag on the sample, printing the grammar above | Claims about platform machinery: accessibility trees, network stacks, storage, collectors | Every build on the two shipping clients; see Part 4 |
| **N — not applicable** | — | Must carry a written reason and a date | — |

**Tier S is the goal for every claim it can honestly hold**, and the discipline is that a claim
lands in tier C only when the platform is genuinely the thing under test. `A1` (a batch applies
whole or not at all) is tier S because `BatchValidation.kt` is one implementation all four clients
share — proving it once proves it everywhere. `D4` (activation drives the guest) is tier C because
the accessibility layer that delivers the activation is different code on every client.

Getting that boundary wrong in the permissive direction is the expensive mistake: a claim marked S
that actually depends on platform behaviour is a claim nobody is testing on three of four clients,
and it will read as green.

## Part 2 — The capability catalogue

Rows are the architecture's own promises, taken from the specifications rather than invented here.
"Today" is the honest state as of 2026-09-04.

**The Android column is graded against the minified release build.** Since 2026-09-07 the
instrumented drills attach to the R8-shrunk APK (`testBuildType = "release"`), so every Android
cell below is a claim about what a user would install, not about a debug build
([ADR-056](../adrs/layer-5/ADR-056-the-engine-survives-code-shrinking.md)).

### A. Protocol and containment

| ID | Claim | Tier | Today |
|---|---|---|---|
| A1 | A batch applies whole or not at all; a rejected one leaves the previous tree drawing | S | ✅ `TransactionalApplyTest`, `WebTreeTransactionTest` |
| A2 | An unknown widget tag becomes a placeholder and later indices in the batch stay correct | S + C | S ✅; C Android, iOS, web |
| A3 | An unknown property on a widget with no affordance is ignored and the widget renders | S + C | S ✅; C Android, iOS, web |
| A4 | An unknown property on a widget that owns an affordance withholds the widget | S + C | S ✅; C Android, iOS, web |
| A5 | Out-of-range numeric values are clamped and reported, never thrown | S + C | S ✅; C Android + iOS |
| A6 | An undecodable batch is rejected whole and reported | S | ✅ |
| A7 | Node identity survives a code update, so `remember` is preserved | S + C | S ✅; C ✅ web — a real Worker swap, with a control proving the tab had moved |

### B. Delivery and trust

| ID | Claim | Tier | Today |
|---|---|---|---|
| B1 | A payload signed by an untrusted key is refused before it runs | S + C | S ✅; C web ✅ (ADR-062) |
| B2 | Key rotation: a manifest carrying both signatures is accepted by clients holding either | S + C | S ✅; C web ✅ (ADR-062) |
| B3 | A payload naming a dictionary version this client lacks is refused before it starts | S + C | S ✅; C ✅ web, android, ios (ADR-061) |
| B4 | Delivery failure leaves the last known-good payload serving | S | ✅ |
| B5 | A guest script whose bytes do not match the digest in the signed sidecar never executes; the Worker is built from the verified bytes | C | C web ✅ (2026-09-15; ADR-062, ADR-032 notes) |
| B6 | A payload that declares a **generated library tier** the host has not registered is refused before a Worker or a guest exists, naming the segment | C | ✅ web, with its control; android and ios by `tools/skew-drill/run-material-preflight.sh` |
| B7 | With two releases live at once, a client in each cohort loads **its own release's** modules and reaches `updated`, not merely `verified` | C | ✅ `tools/reference-server/cohort-drill.sh` (2026-09-17; ADR-077) |
| B7-distinct | …and the two live releases genuinely publish their modules at different addresses, so `B7` is grading routing rather than a coincidence | C | ✅ same drill; it caught `B7` passing hollow on its first run |
| B7-spared | …and the client outside the pin loads the live release's modules at the same time | C | ✅ same drill |
| B8 | The web profile's counterpart: with two releases live, a page loads **its own release's** guest script rather than whichever was published last | C | ✅ `tools/conformance/run-web.sh` (2026-09-17; ADR-077) |
| B8-distinct | …and the two releases genuinely publish their scripts at different addresses | C | ✅ same drill; watched to fail with the addressing disabled |
| B8-canary | …and the cohort that would have kept working by luck is named, because with one shared address it is whichever published last | C | ✅ same drill |

`B3` reads "before it starts" rather than "before any guest code runs", and the change of wording is
a correction rather than a weakening. On the **web** nothing of the payload executes: the host
fetches and verifies the sidecar itself, so it refuses without ever creating the Worker, and the
drill asserts `workerCreated=false`. On **mobile** Zipline exposes no manifest-only fetch —
`loadOnce` fetches, verifies and evaluates the modules in one call, and `fetchManifestFromNetwork`
and `LoadedManifest` are `internal` in zipline-loader 1.27.0 — so the check runs after module
evaluation and before `start`: no entry point is called, no service is bound, nothing composes, and
the interpreter is closed. See
[ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md).

`B1` and `B2` earn their web cells from `tools/conformance/web_services.py` rather than from
`dev.dogwood.host.SignatureTest`, which exercises a verifier the web host does not compile. See
[ADR-062](../adrs/layer-3/ADR-062-a-signed-web-sidecar.md).

### C. Host resolution

| ID | Claim | Tier | Today |
|---|---|---|---|
| C1 | A theme swap repaints every reader with zero guest traffic | S + C | S ✅; C — |
| C2 | An unknown colour token, text style or icon degrades and is reported | S | ✅ |
| C3 | Formatting uses the device's locale; the number crosses, not the rendered string | S | ✅ |
| C4 | Plural category comes from host rules, and a language without rules says so | S | ✅ |
| C5 | A recipe re-resolves when the environment changes, with no wire traffic | S + C | S ✅; C — |

### D. Interaction and accessibility

| ID | Claim | Tier | Today |
|---|---|---|---|
| D1 | Guest-composed text reaches the platform's accessibility layer | C | ✅ iOS, Android |
| D2 | Every exposed element announces something — no anonymous elements | C | ✅ iOS, Android |
| D3 | A guest-composed control is exposed **as a control**, not as text | C | ✅ iOS, Android |
| D4 | Activating through the accessibility layer drives the guest and changes the tree | C | ✅ iOS, Android, web — including by keyboard on web |
| D5 | The screen scrolls through the accessibility layer | C | ✅ iOS, Android |
| D6 | Text input is host-authoritative: mask, limit and counter apply where the typing is | S + C | S ✅; C — |
| D7 | A disabled control is announced as disabled | C | ✅ Android; iOS reports none on screen |
| D8 | A guest can move focus, and a code update neither loses it nor takes the keyboard back | S | ✅ both halves — guest and host binding |
| D9 | A guest can drive and observe a scroll position on a declared quantum, and it survives a code update | S | ✅ both halves — guest and host binding |
| D10 | A guest can drive and observe a list's position; a target for an item that does not exist yet waits rather than clamping | S | ✅ both halves — guest and host binding |
| D11 | A guest can ask the host for something and **wait for the answer**; a reply carries the request it answers | S + C | ✅ S on three targets; C Android |
| D12 | A guest can declare where a sheet should be and be told where the **user** left it | S | ✅ both halves — guest holder and host mirror |
| D13 | A modal interrupts, and closing it removes its content rather than hiding it | S | ✅ `DialogTest` |

### M. The generated library tier

Everything else in this catalogue grades a surface somebody wrote. This family grades one nobody
wrote: [ADR-072](../adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md)'s
Material 3 tier, emitted from the library's own sources, and the question is whether a *generated*
binding survives the whole path -- payload, guest stub, wire, host binding, the real library, the
platform's accessibility layer, and back to the payload's own state.

The screen is `MaterialScreen.kt` in `samples/slice-screens`, which all four clients render from
its own entry point. Every control on it has a **witness** beside it: a line of primitive-tier text
whose content is a function of that control's state. Four clients read a screen through four
different instruments, and a line of text is the observation all four can make -- so every claim
below ends at the witness rather than at a property that ought to imply it (AGENTS.md section 1.5).

| ID | Claim | Tier | Today |
|---|---|---|---|
| M1 | Every section of the generated catalogue renders: its components' own labels reach the screen | C | ✅ android, ios, web |
| M2 | A generated button is operable through the accessibility layer and the payload's state changes | C | ✅ android, ios, web |
| M3 | Generated selection controls are operable and their state changes: checkbox, switch, radio | C | ✅ android, ios, web |
| M3-announced | …and an assistive technology is told what they are and whether they are on | C | ✅ android; ios and web report what their platform publishes instead, as skips carrying the observation |
| M4 | A generated dialog opens, is announced, and confirms | C | ✅ android; web announces but cannot be operated from outside the process; **iOS blocked by the overlay itself** |
| M5 | A generated sheet and menu open and choose | C | ✅ android, web; **iOS blocked by the overlay itself** |
| M6 | A primitive-tier icon inside a generated component announces its description | C | ✅ android, ios, web |
| M7 | A generated slider is moved through the accessibility layer and reports its value | C | ✅ android, ios; web publishes no node for one |

**Two cells say "not established", which is neither a pass nor a failure — and 2026-09-16's runs
found out why.** The iOS drill grades `M1`, `M2`, `M3`, `M6` and `M7` in about six seconds and then
stops, entirely, at the first activation of a Material 3 **overlay**: a dropdown menu, or a dialog
when the claims are reordered so that one comes first. Moving the slider claim away changed nothing;
moving the dialog to the end simply moved the stop to the menu. It is not slowness — it is past the
point the drill's own deadline can fire, which from outside a process is what a blocked main thread
looks like.

The web drill met the same shape from the other side: with a Compose dialog open, that client
answers no input at all — not an accessibility click, not a mouse event at the node's own box, not
Escape. Two clients, two instruments, one behaviour. Both are recorded in
`tools/upstream-reports/README.md` and neither is a statement about a generated binding: the tier's
own render tests confirm this dialog and this menu on the Java Virtual Machine, WebAssembly **and
the iOS simulator**, where Compose's test framework drives the composition rather than the
platform's accessibility layer.

The claims are ordered so that a run grades everything it can before it stops at the thing that is
actually wrong, rather than hiding the stop behind an order that happens to work. Nothing has been observed to fail: the same two claims pass on
Android, and the dialog and sheet bindings pass in the tier's own render tests *on the iOS
simulator*. An empty cell here means the drill has not settled the question, which is the rule this
matrix has always used and the reason its empty cells mean something.

Three of these cells are **skips carrying an observation** rather than failures, on the precedent
`D7` set: what they record is Compose Multiplatform's accessibility bridge on that client, not the
generated binding, and the binding itself is covered by `Material3FamiliesTest` on the Java Virtual
Machine, WebAssembly and the iOS simulator. The observations are in
[`plans/material3-proof.md`](material3-proof.md) section 5 and drafted in
`tools/upstream-reports/README.md`.

**No desktop column, deliberately.** The desktop client has no accessibility tree a drill can walk
from outside the process -- that is why its skew drill reads a render transcript instead -- and
every claim here is about what an assistive technology can find and operate. What the desktop does
carry is `MaterialReplayTest`, which renders a real payload's own change batches through the real
host bindings on the Java Virtual Machine and asserts every section's labels and witnesses. That is
the rendering half of `M1` on every pull request, without a device; the interaction half is what
the three device columns are for.

`B6` belongs with this family in spirit and sits in `B` because it is a delivery claim: a payload
that declares a generated tier the host does not have is refused before any guest code runs.

### H. Release control — surviving a bad publish

The half the architecture's selling point had been missing. Shipping without a store review is only
half a capability; **un-shipping** without one is the other half, and until
[ADR-049](../adrs/layer-5/ADR-049-surviving-a-bad-publish.md) nothing here had it.

| ID | Claim | Tier | Today |
|---|---|---|---|
| H1 | A payload version that started is remembered across process death | S + C | ✅ S; C Android |
| H2 | A crash loop terminates — a version that repeatedly fails to work is quarantined | S | ✅ (the device half cannot be provoked; see Part 5) |
| H3 | A quarantined version names the last release known to have worked | S | ✅ |
| H4 | A publisher can stop a release from a signed field in the manifest | S + C | ✅ S; C Android |
| H5 | A refusal leaves a working guest running and says why | C | ✅ Android |
| H6 | A release forgiven by a later success is not refused forever | S | ✅ |

### I. Authoring — what a guest may not be

| ID | Claim | Tier | Today |
|---|---|---|---|
| I1 | Guest code calling a per-frame animation API fails the build | S | ✅ |
| I2 | Guest code calling a resource loader fails the build | S | ✅ |
| I3 | The check does not fire on comments, strings, or similarly-named code | S | ✅ |

### J. Host services — what a guest may ask its host for

The surface a host wires and a guest calls: a log, a clock, feature flags, analytics, navigation,
the network, the launch parameters an experience opens with, and the dictionary versions the client
implements. It has existed since Phase 4 on mobile and was **never graded**, which is why the web
profile could ship without any of it and nothing said so — for as long as the web guest existed, the
sample's own Diagnostics screen read `surface revision 0 (unreported)` and `host clock unavailable`,
and every summary of that client said it ran "the same screens as the mobile payload". It did. It
ran them blind.

| ID | Claim | Tier | Today |
|---|---|---|---|
| J1 | The services a host wired reach the guest, and the guest can read them | C | ✅ Android, iOS, web |
| J2 | Launch parameters reach the experience **the host named** | C | ✅ Android, web |
| J3 | The dictionary versions this client implements reach the guest | C | ✅ Android, iOS, web |
| J4 | A route the host does not handle is declined, and recorded as skew rather than dropped | C | ✅ Android, web |
| J5 | Host and guest read **one** declaration of the start payload, not two | S | ✅ `WorkerPayloadTest` |

`A7` is graded on the web by this group's drill rather than by the skew one, and the placement is a
statement rather than filing: **what carries a guest's state across a code update on this platform
is the start message**, so the two are one mechanism. Until 2026-09-07 the web profile had never
performed a code update at all — the normal case on this architecture, and its whole selling point.
It does now, with `restoredState` riding the same message; the drill moves off the default tab,
publishes, and asserts the screen comes back where the user left it. Watched to fail with the
restore removed.

**`J1` and `J3` are graded on all three shipping clients**, and on Android and iOS by the drill that
already had the Diagnostics screen and the tree walk in hand — a separate drill would have been a
second copy of both to assert on strings the first had already collected. Each reads a real
millisecond count off the screen, which is the value a guest could not have invented and which shows
as `host clock unavailable` when nothing crossed.

**`J4` found a real gap on the way to being graded, which is the argument for the whole group.**
`SkewReport.unknownRoutes` has existed since it was written — *"routes a guest asked for that this
client does not handle; the host stayed where it was"* — and **nothing on mobile ever filled it**.
An unknown route was declined and silently dropped. The web records it inside
`DogwoodWebExperience`, because there the navigate call passes through the experience; on mobile it
does not, and cannot: a navigation service is constructed by the **application**, before any
experience exists, and handed in. So the engine has no seam at which to record this and the host is
the only thing that can. The Android sample now does, and is the reference for it.

**`J2` and `J4` are not graded on iOS**, and the reason is the host rather than the drill: that
sample wires no navigation service at all, which is a legitimate choice the surface explicitly
allows — every service is optional and its absence is normal. Its launch parameters are graded by
nothing, which is an evidence gap and stays written down as one.

**Two things about the Android drill had to change to grade `J4`, and both were the drill lying.**
It scrolled by asking whether a container *accepted* the action, and the Diagnostics screen
demonstrates a **nested** scrolling container that accepts one forever — forty accepted actions, a
motionless page, and a control four sections below reported unreachable. And it settled for a fixed
400 ms after a scroll, so the page moved and the tree had not been rebuilt when it looked. Both are
the same mistake in different clothes: asserting a schedule rather than an outcome.

`J5` is the one that is not about a platform. The host half of the web boundary is
Kotlin/WebAssembly and the guest half is Kotlin/JavaScript; they do not link, so the envelope's
*kind* constants are mirrored by hand in each. The payloads deliberately are not — they live in
`dogwood-wire`, the one module both halves compile — and `WorkerPayloadTest` is what makes that
checkable rather than merely stated, including the tolerance a newer field depends on, with a
control proving the fixture really carries one.

### K. Compatibility across the over-the-air gap

Three toolchains are coupled — the application's, the engine's and the payload's — and the pairing
every real deployment has is a payload built **earlier** than the host running it, because users
update applications slowly. `docs/getting-started.md` states the policy (hosts first, payloads after
the fleet); a policy is not a test, so `tools/conformance/cross-version.sh` serves a **committed,
signed payload fixture** to a host built from current sources.

| ID | Claim | Tier | Today |
|---|---|---|---|
| K1 | A payload built by an earlier toolchain still loads and **verifies** on a host built today | C | ✅ desktop, android, ios |
| K2 | …and still **renders**: it composes its screen, not merely loads | C | ✅ desktop, android, ios |
| K3 | A host built from an **earlier engine** refuses a payload built today that declares more than it implements, before `start` and naming the segment | C | desktop, `tools/conformance/engine-skew.sh` |
| K4 | …and a host built today **runs** a payload the earlier engine built | C | desktop, `tools/conformance/engine-skew.sh` |

`K1` and `K2` serve a **frozen payload artifact** to a host built today, which covers a payload that
is merely old. `K3` and `K4` are the pairing that needed a second *engine* version to exist and so
read "blocked" in the audit until one did: `tools/conformance/engine-skew.sh` builds the host in a
worktree at a tag, builds the payload at `HEAD`, and serves each to the other. The `K3` direction is
the one a deployment reaches by shipping a payload faster than a store review, and it must end in a
refusal rather than a degraded screen.

The fixture is an artifact rather than a rebuild, and `tools/conformance/fixtures/README.md` says
why: a rebuild is today's toolchain, which is the pairing already covered by every other drill here.
Only something that stops changing can be old. Adding a newer fixture beside the current one widens
the window covered; retiring one is either a compatibility fix or a documented support decision.

### E. Lifecycle and resources

| ID | Claim | Tier | Today |
|---|---|---|---|
| E1 | State survives process death and is restored | S + C | S ✅; C Android + iOS |
| E2 | An evicted experience cold-starts and restores rather than starting over | C | Android + iOS |
| E3 | A code update leaves no live reference to the previous guest generation | S + C | S ✅ (JVM); C iOS soak |
| E4 | No cross-language reference cycles | C | iOS only — genuinely iOS-shaped, see Part 5 |

### F. Network and security

| ID | Claim | Tier | Today |
|---|---|---|---|
| F1 | Data requests are default-deny; a host must be named to be reachable | S + C | ✅ S; C Android, iOS |
| F2 | Every redirect hop is re-checked against the allow rule | S + C | ✅ S; C Android, iOS |
| F3 | Images are policed on their own allow rule, not the data one | S + C | S ✅; C Android |
| F4 | Cleartext is opted into per host and per scheme | S + C | ✅ C Android, iOS — including the `file://` case that shipped |

### G. Performance

| ID | Claim | Budget | Tier | Today |
|---|---|---|---|---|
| G1 | Guest recomposition p95 | 8 ms | C | Android, iOS, desktop |
| G2 | Batch crossing, per-frame | 4 ms | C | Android, iOS, desktop |
| G3 | Collection pause p99 | 16.7 ms | C | Android, iOS, desktop |
| G4 | Cold start to first composition | 500 ms | C | Android, iOS, desktop |
| G5 | The host's weight: the WebAssembly modules, `app.js` and `index.html`, brotli | 4,060,000 bytes | C | web only |
| G6 | The guest payload script's weight: the content-addressed `guest-kotlin-<digest>.js`, brotli | 252,000 bytes | C | web only |

`G5` and `G6` are bytes rather than milliseconds, and they are two budgets rather than one for a
reason that is about *when* each is paid. The host is downloaded once and then held behind an
immutable content hash, so its bytes cost a visitor once. The guest script is the Over-The-Air
(OTA) payload and is re-fetched whenever a product publishes, so its bytes cost a visitor once per
release. Adding them together would average two different prices, and it would make a payload
change read as a client regression.

Both rows read as a number rather than as "recorded, not gated", because both are compared to
`tools/conformance/budgets.tsv` by a grader that fails: `tools/conformance/from_web_weight.py` for
`G5` and `tools/conformance/from_guest_weight.py` for `G6`. `G5` runs on every pull request
(`.github/workflows/conformance.yml`) and `G6` runs nightly
(`.github/workflows/tier-c.yml`); both measure a property of the build rather than of the machine,
so `G6` belongs beside `G5` in the per-pull-request job and moving it there is one step in
`conformance.yml`.

`G6` exists because `G5` deliberately excludes the guest script and therefore nothing bounded it:
the generated Material 3 tier grew the guest as well as the host, and only the host half was
caught. Both ceilings carry the attribution rule that `budgets.tsv` states -- **a raise arrives
with three builds in the commit that makes it**, or it is a number nobody has to defend.

## Part 3 — The matrix

**Generated by `tools/conformance/aggregate.py`.** Do not edit by hand: a matrix that drifts from
reality is worse than none, because it is a document asserting that something is tested.

```
tools/conformance/run-all.sh
```

Two things write it. `tools/conformance/run-all.sh` on a machine holding every device, and the
nightly `.github/workflows/tier-c.yml`, which boots an iOS simulator on a macOS runner and an
Android emulator on an ubuntu one and grades the same claims. Either way the writing is done by
`aggregate.py --update-plan`, which replaces everything between the two markers below and
**refuses** if they are not both there -- an appended second matrix, one of them stale, is worse
than the drift this replaces.

The raw runs are committed beside the tools as `result-<client>-<date>.conf`: the newest are
`result-<client>-2026-09-14.conf` and the run before, 2026-09-09 at commit `ce8e8cb`, is
`result-<client>-2026-09-09.conf`. The nightly publishes the same files as a build artifact.

The second framework grading flagged that this matrix had gone stale against prose totals -- the
repository's own rule, broken at its own finish line -- so regeneration belongs to the same commit
as the claims it grades, and now has a machine that does it.

<!-- conformance-matrix:begin -->
*Generated 2026-09-14 on branch `production-review` (pull request #62), by `tools/conformance/run-all.sh` with `SKIP_ENGINE_BUILD=1` grading the build that had just run green.*

| Claim | android | desktop | ios | web |
|---|---|---|---|---|
| A1 | ✅ | ✅ | ✅ | ✅ |
| A2 | ✅ | ✅ | ✅ | ✅ |
| A3 | ✅ | ✅ | ✅ | ✅ |
| A4 | ✅ | ✅ | ✅ | ✅ |
| A5 | ✅ | ✅ | ✅ | ✅ |
| A6 | ✅ | ✅ | ✅ | ✅ |
| A7 | ✅ | ✅ | ✅ | ✅ |
| B1 | ✅ | ✅ | ✅ | ✅ |
| B2 | ✅ | ✅ | ✅ | ✅ |
| C1 | ✅ | ✅ | ✅ | ✅ |
| C2 | ✅ | ✅ | ✅ | ✅ |
| C3 | ✅ | ✅ | ✅ | ✅ |
| C4 | ✅ | ✅ | ✅ | ✅ |
| C5 | ✅ | ✅ | ✅ | ✅ |
| D6 | ✅ | n/a | ✅ | ✅ |
| D8 | ✅ | n/a | ✅ | ✅ |
| D9 | ✅ | n/a | ✅ | ✅ |
| D10 | ✅ | n/a | ✅ | ✅ |
| E1 | ✅ | ✅ | ✅ | ✅ |
| E2 | ✅ | ✅ | ✅ | ✅ |
| E3 | ✅ | ✅ | ✅ | ✅ |
| F1 | ✅ | ✅ | ✅ | n/a |
| F2 | ✅ | ✅ | ✅ | n/a |
| F3 | ✅ | ✅ | ✅ | n/a |
| F4 | ✅ | ✅ | ✅ | n/a |
| H1 | ✅ | ✅ | ✅ | ✅ |
| H2 | ✅ | ✅ | ✅ | ✅ |
| H3 | ✅ | ✅ | ✅ | ✅ |
| H4 | ✅ | ✅ | ✅ | ✅ |
| H6 | ✅ | ✅ | ✅ | ✅ |
| I1 | ✅ | ✅ | ✅ | ✅ |
| I2 | ✅ | ✅ | ✅ | ✅ |
| I3 | ✅ | ✅ | ✅ | ✅ |
| D11 | ✅ | n/a | ✅ | ✅ |
| J5 | ✅ | ✅ | ✅ | ✅ |
| D12 | ✅ | n/a | ✅ | ✅ |
| D13 | ✅ | n/a | ✅ | ✅ |
| D14 | ✅ | n/a | ✅ | ✅ |
| D15 | ✅ | n/a | ✅ | ✅ |
| D16 | ✅ | n/a | ✅ | ✅ |
| D1 | ✅ | n/a | ✅ | ✅ |
| J1 | ✅ | n/a | ✅ | ✅ |
| J3 | ✅ | n/a | ✅ | ✅ |
| D2 | ✅ | n/a | ✅ | ✅ |
| D3 | ✅ | n/a | ✅ | ✅ |
| D5 | ✅ | n/a | ✅ | ✅ |
| D4 | ✅ | n/a | ✅ | ✅ |
| D7 | ✅ | n/a | ✅ | n/a |
| J4 | ✅ | n/a | ✅ | ✅ |
| J2 | ✅ | n/a | — | ✅ |
| K1 | ✅ | ✅ | ✅ | — |
| K2 | ✅ | ✅ | ✅ | — |
| G1 | · | n/a | · | — |
| G2 | · | n/a | · | — |
| G3 | · | n/a | · | — |
| G4 | · | n/a | · | — |
| B3 | ✅ | — | ✅ | ✅ |
| E4 | n/a | n/a | ✅ | n/a |
| H5 | — | — | — | ✅ |
| G5 | — | n/a | — | ✅ |

✅ met · · nothing here to judge · n/a exempt, see `exempt.tsv` · ❌ failed · — gap

- `desktop` is not graded on D: desktop is a development loop, not a shipping target
- `desktop` is not graded on G: desktop is a development loop, not a shipping target
- `desktop` is not graded on E4: one collector, so no cross-language cycles are possible
- `android` is not graded on E4: one collector, so no cross-language cycles are possible
- `web` is not graded on E4: one heap, so no cross-language cycles are possible
- `web` is not graded on F: the web profile's network policy is the browser's Content Security Policy, enforced by the browser rather than by Dogwood; ADR-032 records that this is weaker than the mobile guarantee rather than equal to it
- `web` is not graded on D7: Compose Multiplatform publishes role and name only for a web accessibility element -- a disabled control reaches the tree as <div role="button"> with no properties at all, so nothing distinguishes it from the enabled button beside it. The composition marks it disabled and the platform drops that; drafted as upstream report 3
- `desktop` is not graded on J1: desktop is a development loop, not a shipping target
- `desktop` is not graded on J2: desktop is a development loop, not a shipping target
- `desktop` is not graded on J3: desktop is a development loop, not a shipping target
- `desktop` is not graded on J4: desktop is a development loop, not a shipping target

- **android**: pass 65, skip 4
- **desktop**: pass 44
- **ios**: pass 64, skip 4
- **web**: pass 59, skip 1

<!-- conformance-matrix:end -->

**`G5` moved, and the attribution is why it was allowed to.** The catalogue work took the shipped
web slice from 3,643,599 to 3,766,502 bytes brotli, past the old 3,700,000 ceiling. Three builds
place every byte of the growth in the application's own WebAssembly module — Skiko is byte-identical
across all three — and split it: the five components of C1 and C2 cost 25,979 bytes between them,
and **C3's two Material 3 pickers cost 96,924**, roughly half a second of Fast 3G waiting for two
components most screens never show.

The ceiling is now 3,900,000, by owner decision
([ADR-066](../adrs/layer-5/ADR-066-the-pickers-cost-half-a-second.md)): a design system's components
cost every client globally, which is what mobile already does, and one answer to "what does adding a
component cost?" is worth more than the bytes. Per-component binding is deferred as `D1`.

Raising a budget because you crossed it is the move `budgets.tsv`'s own comment warns about, so the
raise now carries a price: **every raise of `G5` must arrive with an attribution in the commit that
makes it.** That is the difference between this and the failure the comment describes.


## Part 4 — How it runs, and what stops it rotting

**Gating.** Tier S runs in `./gradlew build` and blocks the merge today. Tier C blocks the merge on
Android and iOS once each client's drill exists; web joins when its drill exists.

**Flake is the real cost of gating**, so three rules, each of which this project already learned the
hard way:

- **Every drill carries a negative control**: the gate is watched to fail with the fix reverted.
  A drill without a demonstrated failure is not admitted to the gate.
- **A red claim is validated before it is believed** (`AGENTS.md` §1.5). Two accessibility claims
  failed against a *property* — a node's own text, a node's `focusable` flag — where the real
  screen reader aggregates a subtree and the real browser routes focus through the canvas. Neither
  was a real defect.
- **A drill that cannot run REFUSES rather than fails.** The accessibility drill refuses when no
  assistive technology is running, because twenty failures from one cause reads as a broken screen
  rather than an unconfigured machine. Refusal is a distinct exit code and does not report as a
  test failure.
- **A new drill soaks before it gates**, and the number is chosen for what it is measuring rather
  than copied. The leak suite ran fifty times because its risk was a *garbage collector* behaving
  differently run to run — genuinely random, so only repetition characterises it. The device drills'
  risk is different: every failure seen while building them had a deterministic cause with a name
  (stray Chrome helpers starving SwiftShader, the Kotlin/Wasm klib checker, a shell pipeline
  reporting `SIGPIPE` as a product failure), and repetition does not characterise a cause that is
  already understood. **Ten consecutive full-suite runs, 2026-09-05, all green**
  (`tools/conformance/soak-2026-09-05.log`) — roughly an hour of real device work. If a flake with
  no identified cause ever appears, that is when fifty becomes the right number again.

**The matrix is generated, not maintained.** `tools/conformance/aggregate.py` reads the `CONF` lines
from every client's run and writes Part 3. A hand-maintained matrix drifts from reality, and a
matrix that drifts is worse than none — it is a document that says the thing is tested.

**The new-client checklist.** When a fifth client is proposed, Part 2 *is* the specification of what
it must do, and the matrix row it starts with is empty. That is the deliverable of this plan: the
question "what does it take to add a client?" has a written answer that is not "find out".

## Part 5 — Deliberately not covered, with reasons

Recorded so that an absence is a decision somebody can point at.

- **Whether the speech is good.** Nothing automated hears anything. `D1`–`D5` prove the surface is
  correct and operable; whether "Expand" is the right word, and whether the reading order makes
  sense, is a human judgement and stays one.
- **Typing and selection as experienced.** `D6` proves the host is authoritative for the text; the
  input method editor, the keyboard appearing and selection handles are driven by touch and
  hardware input that `simctl` and `adb` cannot faithfully supply.
- **`D7`, disabled controls, is unclaimed because the sample has no disabled control on screen.**
  The check is written and reports zero. Adding one to the sample is a small task and is in Part 6;
  until then the row is honest about being untested rather than counted as passing.
- **`E4`, cross-language cycles, is iOS-shaped and stays there.** It exists because Kotlin/Native's
  tracing collector and Objective-C's reference counting cannot see each other's graphs. Android and
  desktop have one collector; web has one heap. Marking this `n/a` elsewhere is a statement about
  the platforms, not an exemption.
- **`D8`'s tier-C half has no drill.** The shared tests prove what crosses the boundary — a request
  is a direction and a count, a holder that was not passed sends nothing, and a replacement guest
  neither reissues the last request nor loses the ability to make a new one. What no assertion here
  covers is whether the **keyboard actually moves**, which is the same limitation `D6` records: the
  input method is driven by hardware input that `adb` and `simctl` cannot faithfully supply. It was
  verified by hand on an Android emulator, reading `dumpsys input_method` rather than a screenshot
  ([ADR-043](../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md)), and a hand-run is
  evidence for a record and not for a matrix cell. The cell stays blank.
- **`D8` and `D9` are tier S on both halves**, which is a correction rather than an achievement.
  They were first written as "S for what crosses, C by hand for what the host does", citing ADR-014's
  claim that exercising a host binding in a composition needs "a Compose UI test harness this project
  does not have". That claim was false when ADR-014 made it and had been repeated twice since:
  `dogwood-host`'s `jvmTest` source set carries `compose.uiTest`, and a dozen tests already used
  `runComposeUiTest`. `FocusMirrorTest` and `ScrollMirrorTest` assert the host halves in a real
  composition, and each was watched to fail with the line it covers removed. What *remains* outside
  any assertion is the same thing `D6` records — the keyboard itself, driven by hardware input that
  `adb` and `simctl` cannot supply.
- **Group `I` is best-effort by construction, and Layer 1 said so first.** A call assembled at
  runtime, aliased behind another name, or reached through reflection is invisible to a source scan.
  It catches a directly-named forbidden API, which is *the* case — nobody reaches for
  `rememberInfiniteTransition` by accident through an alias — and it must never be described as a
  guarantee. Half its tests are about **false** positives, which is the right proportion: a check
  that rejects a comment is a check that gets suppressed, and a suppressed check enforces nothing.
- **`H2`'s device half cannot be provoked, and that is the point.** Quarantine is arithmetic over
  state persisted before a payload runs; the device evidence is that the state *is* persisted
  (`H1`), and the arithmetic is unit-tested as the loop it models. Making a real payload crash on
  launch, on a device, on demand, would mean shipping a deliberately broken guest in the sample —
  which is a thing that can be published by accident, and the one kind of accident this claim group
  exists to survive.
- **Staged rollout is not claimed at all.** `InstallCohort` gives a device a stable bucket a server
  could stage against; choosing which cohorts get which manifest is a server's decision and there is
  no server. The absence is a missing server rather than a missing capability on the device, and
  that distinction is worth keeping because only one of the two is engineering here.
- **Web's network policy is the browser's**, per ADR-032. The claim holds; the instrument is a
  policy header rather than a drill, and the guarantee is weaker than the mobile one rather than
  equal to it.

## Part 6 — Rollout

Each step is a branch and a pull request, and each ends with the matrix regenerated.

**Accessibility comes first, ahead of the machinery.** The first draft of this plan put the grammar
and the aggregator first, on the argument that a catalogue not generated from real runs decays into
a wish list. That argument is still true, but it bought no coverage for two steps, and the gap that
prompted the whole plan is accessibility on three clients that do not have it. So the order is
inverted, and the cost of inverting it is paid rather than ignored: **the Android drill emits the
`CONF` grammar from its first commit**, so step 3 is writing the aggregator against output that
already exists rather than retrofitting three drills that grew their own formats. Defining a text
format is cheap; discovering it was wrong across four implementations is not.

1. ✅ **Accessibility on Android — done.** `UiAutomation` is itself an accessibility service, so
   the drill is an instrumented test reading the tree out of process, the way TalkBack does — the
   opposite of iOS, where no such access exists. 9 claims, gated, negative-controlled. A disabled
   control was added to the sample so `D7` is claimable rather than permanently unknown. **It found
   a cross-client inconsistency introduced by the iOS fix**: Android's Material 3 merges a text
   field's label where iOS's does not, so naming the field in semantics made Android announce it
   twice — "Card number Card number 0/16". Fixed by drawing the label with
   `clearAndSetSemantics`, and re-verified green on both clients. That is the first finding this
   plan's premise predicted: a defect visible only when two clients are graded on one claim.
2. ✅ **Accessibility on web — done.** Compose draws to a canvas, which has no intrinsic
   accessibility, so the first question was whether a tree exists at all. It does: Compose
   Multiplatform publishes a live DOM of roles and names, **inside a shadow root** — which is why
   the drill reaches it through `Accessibility.getFullAXTree` and node handles rather than through
   selectors, and why the first draft found nothing. `D4` passes: a synthesised click on the node
   the accessibility tree named drives the guest and the counter moves. `D4-keyboard` is a
   **known gap** — the elements carry no `tabindex`, so keyboard navigation cannot reach them.
3. ✅ **The grammar and the aggregator — done.** `tools/conformance/`, generating Part 3 from three
   real runs. It earned its keep immediately by catching a **stale committed result**: the saved
   Android file was from the negative-control run, and the generated table showed `D7` red for a
   client that passes. A hand-written matrix would have said whatever it last said.
4. ✅ **Retrofit the drills to the grammar.** Done for the test suites, which was the large half:
   `from_tests.py` maps test classes onto claims and the matrix now covers groups A, B, C, E and F
   as well as D. Still outstanding: the leak soak, Phase 0 and page-weight harnesses, which produce
   numbers rather than verdicts and need a budget attached to each before they can emit `PASS`
   (folded into step 8 below, since a budget is what turns a measurement into a gate).
5. ✅ **Network policy drills on Android and iOS — done.** Both hosts now issue real requests at a
   witness server that records what it received, so a refusal is proved by the **absence of a
   request** rather than by the client reporting one — a client can report a refusal and still have
   opened the connection. The existing tests could not make that claim and said so: the Java Virtual
   Machine one passes a client that fails if called, and the iOS one's header admits it "does not
   test that NSURLSession" behaves.

   It found a cross-client divergence on its first run. A blocked redirect was an explicit refusal
   on Android and a bare `302` with no `failure` on iOS — which a guest checking `failure != null`
   would read as a successful request. The iOS behaviour was deliberate and documented ("the 3xx
   itself is a perfectly ordinary response for a guest to see"), and the reasoning did not survive
   the question: this host follows redirects on the guest's behalf, so a 3xx never reaches a guest
   by any other route, and its only meaning is "policy stopped a hop". iOS now refuses the way
   Android does. Negative control: disabling the allow check turns `F1` and `F4` red *with the
   witness recording the request that got through*.
6. ✅ **Web correctness evidence — done, and not the way this step expected.** It planned to write
   web-specific tests against `WebTree` and the web bindings. The parity build-out deleted both, so
   the claims are inherited from the shared suite instead: one implementation, one set of tests,
   graded on four clients. The step that would have written a second test suite was made
   unnecessary by not having a second implementation.
7. ✅ **Skew containment is re-runnable.** `tools/skew-drill/run.sh` does the whole two-build
   procedure -- install the client at version N, patch the surface and bump to N+1, rebuild only
   the payload, read the rendered tree -- and restores the surface on every exit path including a
   failure, because a permanently skewed surface is a permanently failing lock. Claims `A2`–`A4`
   with a control and a report check; negative control turns `A4` red while `A3` stays green, which
   is the useful part: the two rules are independent and the drill tells them apart.

   Two things the automation had to learn, both recorded in the drill's README. Property tags are
   append-only and the lock enforced it against the drill's own first patch. And `grep -q` inside
   `set -o pipefail` reports a success as a failure -- it exits on the first match, `SIGPIPE`s its
   feeder, and `pipefail` calls that a failed pipeline; the arrival check said "not yet" against a
   file that plainly contained the marker, twice, before the pipe was removed altogether.

   **Closed on 2026-09-06: `A2`–`A4` end to end on iOS and web too.** `run-ios.sh` and
   `run-web.sh` are the same five steps against the other two clients, reading the outcome off each
   platform's accessibility tree — `uiautomator` has no equivalent on either. The web one also runs
   `B3` against a genuinely newer payload rather than a hand-written manifest, which at the time was
   a claim the mobile clients could not make at all: they had no pre-flight dictionary check, so
   their render-time rules were the only containment they had. See
   [ADR-052](../adrs/layer-5/ADR-052-the-skew-drill-on-every-client.md).

   **That asymmetry closed on 2026-09-08.** A payload declares its dictionary in Zipline's signed
   metadata and both mobile clients compare it before `start`, so `B3` is graded on Android and iOS
   by `tools/skew-drill/run-preflight.sh` and `run-preflight-ios.sh` — a second drill rather than a
   mode on the first, because the two assert opposite outcomes on the same screen. Containment stays
   for the payload that declares nothing, which is every payload built before the field existed. See
   [ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md).

   **Each port found a defect on its first run, in host integration code no shared test covers.**
   The web host provided none of the composition locals its bindings read, so `A4` passed while
   `A4-reported` failed — every binding-recorded entry was landing in a throwaway `SkewReport` that
   nothing reads. And iOS never registered the product design system, so Acme's three components
   were inert placeholders on that client alone. This is the whole argument for a per-client tier
   stated as a result rather than as a principle.
8. ✅ **Budgets for the numeric harnesses.** `budgets.tsv` is where the roadmap's thresholds stop
   being prose: `from_phase0.py` reads the Phase 0 results and `from_web_weight.py` the page
   weight, and both emit verdicts. **The gate-validity rule is asymmetric, deliberately.** No host
   here is gate-valid — not the development machine, not the simulator, and not the Pixel 10 Pro
   either, which is a flagship where the Phase 0 gate names an entry tier (Layer 4 ADR-008). So a
   number *within* budget on such a host is a `SKIP`, never a `PASS`, because a comfortable result
   from fast hardware says nothing about slow hardware; a number *over* budget is a `FAIL`, because
   favourable hardware exceeding a budget is damning precisely for being favourable. That keeps
   these numbers doing the one thing they can honestly do today, which is catch regressions.
   Where two hosts speak for one client the slower is preferred — a phone over an emulator running
   on the development machine's own processor.

   The leak soak is **not** a claim and is not given one. It measures whether the leak *evidence*
   is stable, which is a precondition for gating rather than a promise about the product; it
   belongs to step 8.
9. ✅ **Gating, split by what each environment can honestly grade.**

 **Tier S gates in continuous integration** (`.github/workflows/conformance.yml`): the build, the
   shared-code claims via `from_tests.py`, and the page-weight budget — the one performance number
   that is a property of the build rather than of the machine. It runs on every push and pull
   request, and a claim whose evidence did not run fails it rather than reading as absent.

   **Tier C gates locally and before a release**, through `tools/conformance/run-all.sh`, which
   exits non-zero on any red cell. It cannot run on a hosted runner: it needs a booted iOS
   simulator with VoiceOver enabled, an attached Android device, a browser with a graphics stack,
   and the guest being served. Wiring it into continuous integration anyway would produce a green
   tick that means less than it appears to, which is the failure this plan exists to avoid — so the
   workflow file says where the boundary is instead of hiding it.

   **The workflow's first run failed, and the lesson is the one this plan keeps relearning.** It
   was written and merged without ever being run — asserted to pass rather than watched to. What
   it caught was a genuine defect: `LeakDetectorTest.aDetachedNodeIsNotReportedAsALeak` set a
   400 ms threshold on a garbage-collection negative control, which is fine on a fast development
   machine and expires before the collector next runs on a slower one. `dogwoodLeakDetector`'s own
   default is ten seconds and its documentation says why — "a reference merely waiting for the next
   collection is not a leak" — and `BrowserLeakWatcher`, written the same day, carries the identical
   contract. This test predated it and had the defect latent, waiting for a slower machine.

   Note the asymmetry that made it survive so long: the *positive* control in the same class uses a
   200 ms threshold and is safe, because slower hardware only makes a retained reference more
   likely to be reported. Only the negative control is exposed to the timing, which is the general
   rule — a test that asserts something was collected is a test about a collector.

*(Lifecycle and host-resolution drills on the shipping clients — previously step 7 — are folded
into step 6 for web and deferred for mobile: `C1`, `C5`, `E1`–`E3` already carry shared-test
evidence on Android and iOS, so a device drill there verifies wiring rather than behaviour, and
ranks below everything above.)*

---

## Part 7 — Carried forward, not closed

Three things, each recorded where somebody will meet it rather than left to be rediscovered.

- ~~**`A2`–`A4` end to end on iOS and web.**~~ Closed on 2026-09-06 by `run-ios.sh` and
  `run-web.sh`, which found one host-integration defect each. See
  [ADR-052](../adrs/layer-5/ADR-052-the-skew-drill-on-every-client.md). What remains from it is
  smaller and named here rather than dropped: **desktop has no skew drill.** It is a development
  loop rather than a shipping target and is not graded on the per-client groups, so this is
  consistent rather than an omission — but it is the one client where the containment rules have
  never met a real skewed payload.
- **The performance budgets cannot be closed by any host that exists.** `G1`–`G4` read `SKIP`
  everywhere, correctly: the Phase 0 gate names a low-end 2022-tier Android device that this
  project decided not to acquire ([Layer 4 ADR-008](../adrs/layer-4/ADR-008-gate-device-not-available.md)).
  The grading is wired and asymmetric, so the first such device to run it closes or reopens the
  gate without further work, and a regression on faster hardware still fails today.
- ~~**No web row is graded against the real guest.**~~ ✅ **Closed on 2026-09-07.** Until
  [ADR-048](../adrs/layer-5/ADR-048-the-real-guest-runs-on-the-web.md) the web slice's guest was
  hand-written JavaScript, so every web row was evidence about shared *host* code or about a guest
  no product would write. The accessibility drill now loads
  `?manifest=dogwood-manifest-kotlin.json` and asserts on the **same Diagnostics screen** the
  Android and iOS drills use, and the skew drill runs the same guest against a newer dictionary.

  Three things came out of the move that the hand-written guest could not have shown:

  1. **`D2` was failing on a control the payload did not compose.** Compose keeps one transparent
     `<input>` over the focused field to collect keystrokes — it cannot receive them on a canvas —
     and Chrome publishes it as an unnamed `textbox` a screen reader stops on. It is excluded by
     *identifying the element* (its inline style is written with
     `--compose-internal-web-backing-input-*` custom properties), and the exclusion is counted in
     the verdict rather than dropped.
  2. **One viewport is not the screen.** Compose publishes accessibility elements only for what it
     has laid out, so reading the tree once asserts about the top of a page — and the first run
     reported "no Expand/Collapse control" for a control four screens down. The drill now scrolls
     the way a user does: 36 named nodes in one viewport, **178** across the screen. Operating a
     control needs a *live* node as well, not the one that walk returned, because by then Compose
     has taken its element out of the tree.
  3. **`D7` cannot be met on this client, and the reason is not Dogwood's.** A disabled button
     reaches the accessibility tree as `<div role="button">` with a correct name and **no properties
     at all** — indistinguishable from the enabled button beside it. The identical composition
     announces it correctly on iOS. Recorded as an exemption with its reason in `exempt.tsv` and
     drafted as [upstream report 3](../tools/upstream-reports/README.md), rather than as a red cell
     that would sit there forever.
- ~~`D8` and `D9` are asserted on one Java Virtual Machine and claimed for four clients.~~ ✅
  **Closed.** The shared-core tests moved to `commonTest` and now run on the Java Virtual Machine,
  an iOS simulator and a real browser — **236, 77 and 68 tests**. `DogwoodTree` moved with them: its
  own documentation said it renders "a host tree, with no Zipline instance in sight" and named the
  Web profile as a caller, and it had been sitting in the Zipline source set, which is precisely why
  no shared test could reach a host binding on the web.

- **Android renders in its instrumented tests, not its unit tests.** Moving the render tests to a
  shared source set put them on Android's *unit* test target too, where they died on
  `android.os.Build.FINGERPRINT is null` — that target runs against a stubbed framework jar with no
  Android in it. They live in an intermediate `renderTest` source set that the Java Virtual Machine,
  iOS and WebAssembly targets depend on and Android's unit tests do not. Robolectric would be a
  third rendering environment to trust, to run tests whose whole purpose is being run where the code
  runs.

- ~~**A hostile-value clamp fires on the Java Virtual Machine and not on the web.**~~ ✅ **Resolved
  on 2026-09-07, and it was never a clamp.** There is no platform divergence: the web clamps and
  reports the same value, `StarRating.rating=-40.0 outside 0.0..5.0`, in the same place.

  What was actually wrong is worse and was hiding behind it. `runComposeUiTest` returns `Unit` on
  the Java Virtual Machine and `Promise<JsAny?>` on Kotlin/WebAssembly, and the test framework
  awaits that promise **only if the test function returns it**. Every shared render test was written
  with a block body — `render(tree)` as a statement, assertions after — so on the web the
  composition never happened: assertions after the call read an un-composed tree, and assertions
  inside the block were never observed at all. A deliberate `fail()` inside a discarded block was
  watched to **pass** on that target, which is what settled it.

  So the whole shared render suite — 49 tests across six files — was green on the web while
  composing nothing, and the clamp test was **the only one sensitive enough to notice**, because it
  is the only assertion in that file about something a *binding* records during composition. The
  rest are recorded by `HostTree.apply`, or assert an emptiness that an un-composed tree satisfies.
  Filing it as a platform divergence was the wrong conclusion drawn from the right observation.

  Every one of those tests is now an expression body that returns the harness's result, and they
  compose on the web for the first time — watched, by breaking one assertion and reading a real
  browser measurement (`maxOffsetDp=320`) in the failure. `tools/render-shape/check.py` fails the
  build on the old shape and runs on every pull request. See
  [ADR-054](../adrs/layer-5/ADR-054-a-render-test-that-returns-nothing.md).
- ~~`LazyListMirror` has no host test.~~ ✅ Closed: `LazyListMirrorTest` asserts the held target that
  a device found and reasoning did not, the item-granular throttle, and the re-report a replacement
  guest depends on — each watched to fail with the line it covers removed. All three mirrors are now
  asserted rather than demonstrated.
- ~~**Tier C does not gate in continuous integration**, and cannot.~~ ✅ **Closed on 2026-09-16.**
  The sentence was about a private repository's runner minutes rather than about the machines.
  This repository is public, so GitHub's macOS runners cost nothing and its ubuntu runners expose
  `/dev/kvm`, which is what lets an Android emulator run in minutes instead of hours.
  [`.github/workflows/tier-c.yml`](../.github/workflows/tier-c.yml) boots one of each nightly: a
  macOS job for the iOS accessibility, Material, skew and pre-flight drills plus the web drills in
  headless Chrome, an ubuntu job for the Android drills on an emulator, and a third job that folds
  the `result-*.conf` runs into one per client and regenerates Part 3 above. Nightly and on demand
  rather than per pull request, because it is roughly forty minutes of device time and tier S
  already blocks the merge.

  Every step in it invokes one of the drill scripts rather than reimplementing it, and where a
  script hard-codes a macOS path for Chrome it already reads `CHROME` first, so the workflow sets
  the variable instead of forking the script. `run-all.sh` remains the local gate and the run whose
  results are committed; the nightly publishes its own as an artifact and does not commit them.

  **What still cannot be graded is `G1`–`G4`, and the reason has not changed.** They need the
  low-end 2022-tier device the bullet above names. A hosted x86-64 emulator on a shared virtual
  machine is not it: a timing number measured there says which runner the job landed on. They read
  `SKIP` in the nightly for the same reason they read `SKIP` locally. `G5` and `G6` are graded,
  because bytes are a property of the build rather than of the machine.

## Part 8 — What the rollout actually found

Worth separating from the machinery, because the machinery is only justified by this.

**Four defects, none of which any existing test could have caught:**

- A text input reached VoiceOver with **no name at all**, on a screen that had authored the label,
  crossed it over the wire, resolved it host-side and drawn it.
- The same fix then made Android **announce the label twice** — Material 3 merges a text field's
  label where iOS's does not. Visible only when two clients were graded on one claim.
- A blocked redirect was an explicit refusal on Android and a **bare `302` with no `failure`** on
  iOS, so a guest checking `failure != null` would read a blocked request as a successful one.
- `HostTree.clear()` was missing from the mobile hosts entirely — they build a fresh tree per
  experience and never met the bug the web host had already fixed.

**A false alarm the rule caught, and the drill now catches by itself.** `D7` went red on iOS with
`1: [<no label> traits=0x101]` — a disabled control announcing nothing, which would be a real
accessibility defect. It was not one. iOS publishes only what is on screen, and the drill's scroll
loop stops at the *first* frame where its target is reachable, so whatever comes next sits at the
viewport edge with its traits published and its name clipped. Adding an unrelated section to the
sample moved where the loop stopped, and a button that had not changed went anonymous. The finding
was a hypothesis about a control and was really an observation about a viewport. The drill now does
what `AGENTS.md` §1.5 asks of a person — brings the element further into view and looks again before
reporting — and the failure message names the element by its neighbours rather than printing `[]`,
which is what the first version did because its helper filtered empty labels out of a claim about
missing labels.

**One defect in the machinery itself, found by running it.** `tools/skew-drill/run.sh` patches the
surface and restores it on every exit path, which is right; it restored it with `git checkout --`,
which is a different operation wearing the same clothes. That restores the *committed* content, so
it silently discarded whatever uncommitted work was in those four files — and it did, to the surface
change that added `@Holder`, mid-review, with no message and no failure. The drill cannot tell its
own patch from a developer's. It now saves the files it is about to touch and copies those back.
The symptom is worth remembering: the next build failed in a file the drill never mentions, on a
parameter that had existed minutes earlier.

**And a recurring shape, now met a sixth time**: evidence that produced no evidence. The `shared`
scope that filled twenty-one green cells for a client that does not compile the code under test.
A `D4-reverse` check that passed *because* its subject had failed. A page-weight harness whose
number nothing compared to a threshold. Each was found by asking a question the machinery was
supposed to answer and looking at what came back.

**And the opposite shape, met for the third time on 2026-09-14**: a true sentence about the drill
reported as a false one about the product. `D4` said "the Expand button was never reachable" on
three consecutive Android runs, deterministically, after the About screen gained two sections. The
button was on the screen -- a swipe reached it -- but an accessibility scroll moves about a viewport,
the control now sat inside the span one scroll jumps over, and the drill only ever searched forward:
it saw the button in transit, lost it when the scroll settled, and called it unreachable. The first
two times this line appeared the causes were a fixed scroll budget and a nested container at its
end. The drill now settles the tree before reading it, steps back a bounded number of scrolls when
it overshoots -- which is what a screen-reader user does -- and says what was visible when it gives
up, so the fourth occurrence, if there is one, will not need three runs to diagnose. It passed the
same run at 19 of 19, one step back.
