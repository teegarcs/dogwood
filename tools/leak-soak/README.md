# Soaking the Kotlin/Native leak suite

The platform review carried this as a risk rather than a defect: `CrossLanguageLeakTest`'s
**negative control** asserts that an ordinary Kotlin object holding an Objective-C one becomes
unreachable and is collected. That is a claim about a garbage collector's behaviour on a particular
run, not about the code, and a stale stack slot keeping the object alive would report it as a leak
and fail the test — intermittently.

An intermittent failure in a leak suite is worse than no leak suite. A team that has watched it go
red for no reason learns to re-run it, and then learns nothing from the day it goes red for a real
reason. So the gate the review set was: **50 consecutive green runs before the suite is trusted in
continuous integration**, and if it flakes, restructure the control first.

## The run

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
tools/leak-soak/run.sh          # 50 runs, one line each
```

`--rerun-tasks` on every iteration is not incidental: without it Gradle answers 49 of the 50 from
its up-to-date cache, and the soak proves only that caching works.

## Result, 2026-09-03

**50 of 50 passed.** iOS 17.5 simulator, Kotlin 2.3.20, Apple silicon. Full log in
`result-2026-09-03.log`; the gate is met and the suite is trusted as written — **no restructuring of
the negative control was needed**, which is the outcome the review hoped for and did not assume.

Two things this does and does not establish, kept apart because they are easy to blur:

- It **does** establish that the control is not flaky under repetition on this toolchain, on this
  operating system version, on this machine. That is what was asked for.
- It **does not** establish that it cannot flake on a different Kotlin/Native release, whose
  collector is free to change how it scans stacks. This is a soak, not a proof. The reason to keep
  the script rather than delete it after one green run is that the same question has to be re-asked
  the next time the toolchain moves.

A first attempt at this soak was thrown away rather than reported: an unrelated compile error in the
iOS test source set turned runs 15 through 50 red for a reason that had nothing to do with leaks. A
soak that counts build failures as flakes measures nothing, which is why `run.sh` prints the failing
lines rather than only a verdict — the distinction has to be visible in the log.
