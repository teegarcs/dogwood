# The hostile-value drill

[ADR-035](../../adrs/layer-5/ADR-035-hostile-property-values.md) claims that a payload can carry a
number the host recognises and cannot use, and that the result before the fix is not a degraded
screen but a dead application. That claim was made from a unit test. This is the run that made it
on hardware, on both mobile hosts, with a real payload fetched over the network.

## What the guest sent

Three values, added to the Diagnostics screen of the sample guest — each one a value Jetpack
Compose enforces by throwing rather than clamping:

```kotlin
Text("HOSTILE-MAXLINES", maxLines = 0)
Text("HOSTILE-PADDING", modifier = Modifier.padding(-8))
Row(modifier = Modifier.fillMaxWidth()) {
  Text("HOSTILE-WEIGHT", modifier = Modifier.weight(0f))
}
```

Nothing in the guest stubs stood in the way. `maxLines` is filtered on `>= 0` — because absence is
the host-default sentinel and `-1` means "unset" — so **zero passes straight through**. Padding and
weight take the number they are given. A guest author does not have to be hostile to send any of
these; they have to be off by one.

Like the [skew drill](../skew-drill/README.md), the guest change is deliberately **not committed**.
Re-create it by pasting the three lines above into `AboutScreen.kt`, restarting
`:samples:slice-guest:serveProductionWebpackZipline`, and confirming the served payload carries them:

```
curl -s http://localhost:8080/slice-guest.zipline | strings | grep -c HOSTILE   # 3
```

## Before: both hosts die

**Android**, on the emulator, at the moment the Diagnostics tab was opened:

```
E ComposeInternal: java.lang.IllegalArgumentException: both minLines 1 and maxLines 0 must be greater than zero
E AndroidRuntime: FATAL EXCEPTION: main
```

**iOS**, on the simulator, when the drill reached the same tab:

```
DRILL activated=app warm=[app, feed, explore]
Uncaught Kotlin exception: kotlin.IllegalArgumentException: both minLines 1 and maxLines 0 must be greater than zero
    at ... androidx.compose.foundation.text#validateMinMaxLines (HeightInLinesModifier.kt:124)
```

The iOS drill visits every tab in order and never printed the `about` line: the process was gone
before it got there. This is the whole argument for the fix in one detail — the failure is not
scoped to the widget, or the screen, or even the experience. It takes the application with it, on
every client that fetched that payload.

## After: both hosts render, and say what they did

Same payload, same devices, hosts rebuilt with the clamping readers.

**Android** — no `FATAL EXCEPTION` in the log, all three widgets present in the view hierarchy, and
the report on screen:

```
text="HOSTILE-MAXLINES"
text="HOSTILE-PADDING"
text="HOSTILE-WEIGHT"
text="SkewReport(clamped=[Text.maxLines=0 outside 1..2147483647,
                         padding=-8.0 outside 0.0..3.4028235E38,
                         weight=0.0 outside 1.4E-45..3.4028235E38])"
```

**iOS** — the tab the old build died on now activates, and the drill runs to completion:

```
DRILL activated=about warm=[about, app, feed]
DRILL skew=SkewReport(clamped=[Text.maxLines=0 outside 1..2147483647,
                               padding=-8.0 outside 0.0..3.4028235E38,
                               weight=0.0 outside 1.4E-45..3.4028235E38])
DRILL after-trim warm=[about]
DRILL wrote-state-for=[explore, feed, app, about]
```

## One finding, which is the same finding as last time

**The Tabs sample had a `skew` variable that nothing wrote and nothing displayed.** The skew drill's
first finding was that no sample surfaced the report; the display added then did not survive into
the tabbed sample, leaving a dead `var skew by remember { mutableStateOf("") }` behind. Containment
that nobody can see is indistinguishable, from the outside, from containment that is not happening —
and it is why this drill could not have reported anything until the variable was wired up.

Both samples now poll it, and both say in a comment why polling rather than reading: `SkewReport` is
plain sets, written *during* composition, so nothing recomposes when an entry lands.
