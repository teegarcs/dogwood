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

## Reproducing

The drill's surface and guest changes are deliberately **not committed** — a permanently skewed
surface would be a permanently failing lock. Re-create them by adding a component and two
properties as in the table above, then:

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :samples:slice-android:installDebug          # client at version N
# ...edit the surface, bump --version in dogwood-codegen/build.gradle.kts...
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline   # payload at N+1
adb shell am start -n dev.dogwood.slice.android/.TabsActivity      # do NOT reinstall
```
