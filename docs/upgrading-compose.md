# Upgrading Compose, and what a generated tier does about it

Two readers. The engineer bumping Compose Multiplatform, who needs a procedure and needs to know
what the build will refuse. And the engineer on call, who needs to know what an old payload does on
a new host, which is the question a server-driven architecture asks every day and a store-delivered
one never does.

The short version of the second answer, because it is the one people look up in a hurry:

> A payload built against Material 3 `1.9.0` keeps working on a host that has moved to `1.9.1`.
> Every component it uses still exists, because tags are permanent and a removed one stays retired.
> What it may render slightly differently is any parameter it did not set, because **the host
> evaluates the library's own default** — see [§5](#5-the-host-evaluates-the-default-and-that-is-a
> -compatibility-rule).

---

## 1. What moves when you bump

The generated Material 3 tier ([ADR-072](../adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md))
is emitted from the sources of the artifact the host resolves, and its dictionary version *is* the
library's version. So a Compose Multiplatform bump moves four things, of which you type one:

| What | Where | Who moves it |
|---|---|---|
| Compose Multiplatform | `engine/gradle/libs.versions.toml` | you |
| The Material 3 version | the plugin's own mapping (1.10.3 → 1.9.0) | JetBrains |
| The sources the generator reads | resolved from `dogwood-host`'s compile classpath | the build |
| The tier's dictionary version | `1.9.0` encoded as `10900` | the generator |

Since [ADR-073](../adrs/layer-5/ADR-073-a-generated-tier-derives-its-version-and-keeps-its-tags.md)
no version is typed twice. `fetchComposeSources` resolves each module off the host's classpath,
writes `versions.json` beside the extracted sources, and the generator reads the tier's version
from there. `checkGeneratedTierVersions`, which runs in `check`, refuses a committed lock whose
version is not the one this checkout resolves — which is the failure a Compose Multiplatform bump
would otherwise cause silently, because the Material 3 version it maps to is not in your diff.

**`compose.material3` is versioned independently.** 1.10.3 maps to Material 3 1.9.0 today. A
Compose Multiplatform patch release can move that mapping without changing anything you typed.

---

## 2. The procedure

Run from `engine/`, with `JAVA_HOME` set to a Java 21 toolchain.

**1. Bump.** One line in `libs.versions.toml`.

**2. Regenerate, and read the lock's verdict.**

```
./gradlew :dogwood-codegen:generateMaterial3
```

It prints the version the host now resolves, and then one of four things. [§3](#3-the-four-lock-outcomes)
says what each means and what to do. A `Violated` lock fails the build.

**3. Re-measure the surface.**

```
./gradlew :dogwood-codegen:generateComposeCoverage
git diff tools/generator-v2/coverage.md
```

The diff is the honest statement of what the upgrade did to the vocabulary: components gained,
components lost, parameters that stopped being settable. It is committed, so it is reviewable.

**4. Compile the tier, and read what the compiler refused.**

```
./gradlew :dogwood-material3:compileKotlinJvm
git diff engine/dogwood-material3/exclusions.txt
```

Generate, compile, refuse — in that order, because a library is not a surface an author can edit.
A component whose default expression stopped being public lands here with the compiler's own
reason, and the coverage report counts it under *generable, excluded* rather than losing it.

**5. Run the tests that are about the bindings, not about the library.**

```
./gradlew :dogwood-material3:jvmTest :dogwood-material3:wasmJsTest \
          :dogwood-material3:iosSimulatorArm64Test
./gradlew :samples:slice-screens:jsNodeTest :samples:slice-desktop:test
```

The first three are one render test per family on three targets. The last two are the catalogue's
coverage count and the replay of a real payload's wire through the real host.

**6. Run the catalogue on one device.** Whichever you have booted:

```
tools/conformance/run-android.sh        # emulator
tools/a11y-drill/run-material.sh        # simulator, turns VoiceOver on itself
tools/conformance/run-web.sh            # headless Chrome
```

A library upgrade that changes a component's accessibility semantics is invisible to every check
above this one.

**7. Commit the generated files together**: the lock, `exclusions.txt`, the coverage report and
`docs/api/androidx.material3.md`. They describe one state of the world and a commit that carries
three of the four is a commit that cannot be reasoned about.

**8. Ship hosts first.** See [§6](#6-fleet-order).

---

## 3. The four lock outcomes

The lock is `engine/dogwood-material3/androidx.material3.lock.json`. It exists because a client one
dictionary version behind resolves tags numerically: a renumbered tag does not fail to render, it
renders **the wrong thing**.

### Unchanged

The library moved and its bindable surface did not. Commit the version bump.

### Updated — added

New components got new tags above everything taken. Existing tags did not move: a generated tier
allocates from its lock, not from position, precisely so that a library release cannot renumber
what is already published.

A payload may call a new component only once the fleet's hosts have it. The dictionary version is
how that is enforced rather than remembered — see [§4](#4-the-compatibility-matrix) and
[§6](#6-fleet-order).

### Updated — retired

The library no longer declares a component, or an overload now wins its name. The generator keeps
its tag **retired** in the lock's reserved list, so nothing can ever take it, and prints a line
naming it. Payloads in the field that still send the retired tag get an inert placeholder and a
skew report, which is the containment rule graded as `A2` — not a crash, and not the wrong widget.

What to do: nothing, except read the line and decide whether a payload you control still uses it.
`docs/operating.md` §4 is where a skew report shows up in production.

### Violated

The build fails, prints every problem, and writes nothing. A violation is one of:

- **a component or parameter moved tags** — should be impossible with lock-derived allocation, and
  if it happens the generator has a bug rather than the library;
- **a parameter was retyped** (`String` became `TextValue`, an event's argument changed) — a client
  on either side of the change reads the other's encoding with the wrong reader;
- **an enumeration entry was removed or renamed** — a payload still sends the old name;
- **a component was removed without its tag being retired** — see above;
- **the version went backwards** — the library this host resolves is older than the one the lock
  describes. Payloads in the field may declare the higher version and this host would refuse them
  at launch, so it is a decision: `-PdogwoodAcceptTierDowngrade=true` says you mean it.

For a retype, the choices are to exclude the component under its old name (its tag stays retired)
and let the generator bind the new signature under a new name, or to hold the upgrade. **Never
edit the lock by hand.** The lock is the record of what was published; editing it to make a build
pass is deleting the evidence that the build was right to fail.

---

## 4. The compatibility matrix

`P` is what the payload was built against, `H` what the host has.

| P | H | What happens | Graded by |
|---|---|---|---|
| tier 10900 | tier 10900 | renders | `M1`–`M7` on four clients |
| tier 10900 | tier 10901 | renders; every tag still exists; **defaults are the host's library's** ([§5](#5-the-host-evaluates-the-default-and-that-is-a-compatibility-rule)) | `K1`, `K2` |
| tier 10901 | tier 10900 | **refused before `start`**, naming the segment, if the payload declares the tier | `B3` |
| tier 10901 | tier 10900 | if the payload declares nothing: the components this host lacks are placeholders with a report, and the rest of the screen renders | `A2`, `A3`, `A4` |
| uses a component 10901 retired | tier 10901 | that component is a placeholder with a report; everything else renders | `A2` |
| declares the tier | **no tier registered** | refused before `start`, naming `androidx.material3` | `B6` |
| declares nothing | no tier registered | every tier component is a placeholder; the screen's structure holds | `A2` |

Two things worth saying plainly about this table.

**A refusal is the good outcome, not the bad one.** A host that cannot render what a payload needs
refuses the whole release and keeps the last good one, rather than showing a half-drawn screen.
That is [ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md), and it
only works if the payload declares what it needs — which the samples now do, read from the
generator's own output rather than typed.

**Placeholders are the fallback, not the plan.** A payload that declares nothing degrades
component by component. That path is real and graded, and it exists for payloads built before the
declaration existed.

---

## 5. The host evaluates the default, and that is a compatibility rule

The single non-obvious consequence of how this tier works.

A generated binding calls the real library function with the library on the **host's** classpath.
Any optional parameter the payload did not set is passed the default expression copied verbatim
from the library's source — so the value is whatever *that host's* version of Material 3 says it
is, evaluated on the device.

Three consequences:

- **An author who needs one look sets the parameter.** Anything left to the default is a promise
  about behaviour, not about pixels.
- **A fleet mid-upgrade renders two defaults for one payload.** Two devices, same release, slightly
  different corner radius. Nothing is broken.
- **An operator seeing a visual difference between two devices on the same release should check
  the host version first**, before looking for a payload bug.

This is the price of never evaluating a default in the generator, which is what makes the whole
approach work: the generator quotes a default, it does not compute one.

---

## 6. Fleet order

`docs/getting-started.md` states the rule for toolchains: **hosts first, payloads after the
fleet.** For a generated tier it has teeth, because the payload's manifest declares the tier's
version and a host that is behind refuses the release at launch.

1. Upgrade the engine and the host application together. Ship through the stores. Wait for
   coverage.
2. Only then publish a payload that declares the new tier version — which is automatic, since the
   declaration is read from the generator's output at payload build time.
3. If you publish it early, the hosts that are behind refuse it and keep the last good release.
   That is the release guard doing its job, and `docs/operating.md` §3 is how you stop the release.

---

## 7. What this does not cover

Stated because a manual that implies more than it checks is worse than a short one.

- **A default whose *behaviour* changed without its signature changing** is invisible to the lock
  and to every test here. The coverage report will say nothing, because nothing about the surface
  moved.
- **A component whose accessibility semantics changed upstream** is caught only by running the
  Material catalogue on a device (step 6), and only for the families that catalogue covers.
- **Compose Multiplatform's own renderer** changing how something looks is not a dictionary event
  at all. The render tests assert structure and events, not pixels.
- **Kotlin and Zipline versions** are a separate compatibility question with its own rule and no
  equivalent check; `docs/getting-started.md` says so, and `plans/adoption-audit.md` `A6` is where
  the gap is recorded.

---

*Related: [ADR-072](../adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md)
(why the tier is generated from the artifact), [ADR-073](../adrs/layer-5/ADR-073-a-generated-tier-derives-its-version-and-keeps-its-tags.md)
(why nobody types the version), [`plans/material3-proof.md`](../plans/material3-proof.md) (what was
proven about it and how), [`docs/operating.md`](operating.md) (reading a skew report in
production).*
