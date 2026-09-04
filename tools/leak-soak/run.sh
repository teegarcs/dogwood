#!/usr/bin/env bash
# Project Dogwood -- is the Kotlin/Native leak suite trustworthy, or is it flaky?
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/leak-soak/run.sh [runs] [output]
#
# `CrossLanguageLeakTest` asserts two things that pull in opposite directions: that a cycle
# spanning Kotlin's tracing collector and Objective-C's reference counting IS reported, and that
# an ordinary Kotlin object holding an Objective-C one is NOT. The second is the negative control,
# and it is the one at risk -- it asserts that an object became unreachable and was collected,
# which is a claim about a garbage collector's behaviour on a particular run rather than about
# the code. A stale stack slot keeping the object alive would report it as a leak and fail the
# test, intermittently.
#
# An intermittent failure in a leak suite is worse than no leak suite: a team that has seen it go
# red for no reason learns to re-run it, and then learns nothing from the day it goes red for a
# real reason. So it is soaked before it is trusted.
#
# `--rerun-tasks` on every iteration deliberately: without it Gradle would answer 49 of these from
# its up-to-date cache and the run would prove only that caching works.
set -u
RUNS="${1:-50}"
OUT="${2:-$(dirname "$0")/result-$(date +%Y-%m-%d).log}"
cd "$(dirname "$0")/../../engine" || exit 1

: > "$OUT"
for i in $(seq 1 "$RUNS"); do
  log=$(./gradlew :dogwood-host:iosSimulatorArm64Test --rerun-tasks --console=plain 2>&1)
  if echo "$log" | grep -q "BUILD SUCCESSFUL"; then
    echo "run $i PASS" >> "$OUT"
  else
    echo "run $i FAIL" >> "$OUT"
    echo "$log" | grep -E "FAILED|expected|AssertionError" | head -6 >> "$OUT"
  fi
done
echo "DONE" >> "$OUT"

passes=$(grep -c PASS "$OUT")
echo "$passes/$RUNS passed; see $OUT"
[ "$passes" -eq "$RUNS" ]
