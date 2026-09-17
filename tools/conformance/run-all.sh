#!/usr/bin/env bash
# Project Dogwood -- every conformance run this machine can make, then the matrix, then a verdict.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/run-all.sh
#
# This is the tier-C gate. It needs a booted iOS simulator, an attached Android device or emulator,
# Chrome, and the guest being served on :8080 -- which is why it runs here and not in continuous
# integration, where only tier S can be graded honestly (`.github/workflows/conformance.yml`).
#
# Each drill refuses rather than fails when its prerequisite is missing, so a partial run reports
# what it could not do instead of reporting green.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
rm -f "$HERE"/build/*.conf
status=0

split_by_client() {
  python3 - "$HERE/build" "$1" "$2" <<'PY'
import pathlib, sys
out, raw, suffix = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
cur, runs = [], {}
for line in raw.read_text().splitlines():
    cur.append(line)
    if line.startswith('CONF RESULT client='):
        runs[line.split('client=')[1].split()[0]] = cur
        cur = []
for client, lines in runs.items():
    (out / f'{client}{suffix}.conf').write_text('\n'.join(lines) + '\n')
raw.unlink()
PY
}

echo "==> shared-code test evidence"
# Two workers, because the release builds and the iOS framework links this runs in parallel took
# the machine past its memory twice on 2026-09-13 and the process was killed mid-gate. A gate that
# dies of memory reports nothing, which is worse than a slower one.
#
# `SKIP_ENGINE_BUILD=1` reuses the test results already on disk. The build is tier S -- it runs on
# every pull request and it just ran here -- and on a machine also holding an emulator, a simulator
# and Chrome it is the step that tips the memory. Skipping it does not skip the *grading*: an
# absent or stale result is exactly what `from_tests.py` reports as "did not run".
if [ "${SKIP_ENGINE_BUILD:-0}" != "1" ]; then
  "$HERE/../../engine/gradlew" -p "$HERE/../../engine" build --max-workers=2 \
    -x :samples:slice-android:lintDebug --console=plain -q || status=1
else
  echo "    (engine build skipped by request; grading the results already on disk)"
fi
python3 "$HERE/from_tests.py" > "$HERE/build/tests.raw" || status=1
split_by_client "$HERE/build/tests.raw" ""

echo "==> android accessibility and network policy"
"$HERE/run-android.sh" "$HERE/build/android-a11y.conf" >/dev/null 2>&1 || status=1

echo "==> android skew containment"
# Two builds by construction -- a client at version N and a payload at N+1 -- so it is its own
# script rather than another test class. It restores the surface on every exit path.
"$HERE/../skew-drill/run.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew.conf" ]; then
  grep -E "^CONF [A-G][0-9]" "$HERE/../skew-drill/build/skew.conf" > "$HERE/build/android-skew.conf" || true
fi

echo "==> ios skew containment"
# The same two-build procedure as Android's, on a simulator: a client at version N and a payload at
# N+1. It restores the surface on every exit path.
"$HERE/../skew-drill/run-ios.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew-ios.conf" ]; then
  grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/skew-ios.conf" > "$HERE/build/ios-skew.conf" || true
fi

echo "==> desktop skew containment"
# The desktop's instrument is the render transcript rather than an accessibility tree: that client
# has none to walk from outside the process. Same two builds, same three claims.
"$HERE/../skew-drill/run-desktop.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew-desktop.conf" ]; then
  grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/skew-desktop.conf" > "$HERE/build/desktop-skew.conf" || true
fi

echo "==> mobile pre-flight dictionary refusal"
# The same two builds again, with the payload *declaring* the dictionary it was built against, so
# the client refuses before it starts rather than degrading through it (`B3`, ADR-061). Two scripts
# rather than one with a mode: they assert opposite outcomes on the same screen.
for client in "" "-ios"; do
  "$HERE/../skew-drill/run-preflight${client}.sh" >/dev/null 2>&1 || status=1
done
for pair in "android:preflight" "ios:preflight-ios"; do
  name="${pair%%:*}"; file="${pair##*:}"
  if [ -f "$HERE/../skew-drill/build/${file}.conf" ]; then
    grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/${file}.conf" > "$HERE/build/${name}-preflight.conf" || true
  fi
done

echo "==> mobile pre-flight refusal of the generated Material 3 tier"
# `B6`, which is not `B3` again with a different payload. `B3` is a client meeting a dictionary
# *version* it does not have; `B6` is a client meeting a whole *segment* it has never heard of. One
# script with a client argument rather than the pair above, because these two halves assert the same
# outcome and differ only in how the tier is left out -- a build flag on Android, a launch argument
# on iOS.
#
# The web grades `B6` too, in `web_material.py` via `run-web.sh`, and that is two gradings of one
# promise rather than one grading twice: the web host compares versions in `WebDelivery`, a mobile
# host reads the vector out of Zipline's signed manifest metadata in `DogwoodDelivery`.
#
# The drill refuses when the served payload does not declare the tier, which is the prerequisite it
# cannot invent: with nothing to refuse it would pass by testing nothing.
for client in android ios; do
  "$HERE/../skew-drill/run-material-preflight.sh" "$client" >/dev/null 2>&1 || status=1
  if [ -f "$HERE/../skew-drill/build/material-preflight-${client}.conf" ]; then
    # `<client>-materialpreflight`, one hyphen and no more. The fold at the end of this script takes
    # the client from `name.rsplit('-', 1)[0]`, so a file called `android-material-preflight.conf`
    # would be folded into a client named `android-material` and `B6` would land in a column the
    # matrix has never had and nobody reads.
    #
    # The `RESULT` line is carried across as well as the claims. The fold drops it when it merges
    # into an existing `android.conf`, and keeps it when there is none -- which is the case that
    # matters, because a client file with no `CONF RESULT` line is one `aggregate.py` refuses to
    # attribute at all.
    grep -E "^CONF ([A-Z][0-9]|RESULT)" \
      "$HERE/../skew-drill/build/material-preflight-${client}.conf" \
      > "$HERE/build/${client}-materialpreflight.conf" || true
  fi
done

echo "==> a bad publish, quarantined on a device and recovered"
# `H2`'s device half. Every earlier test of the crash-loop quarantine simulated the crash by
# recording a start without a success; this one publishes a payload that genuinely fails to mount.
"$HERE/../reference-server/quarantine-drill.sh" "$HERE/build/android-quarantine.conf" >/dev/null 2>&1 || status=1

# The operational drills that are procedures rather than product behaviour: a key rotated through a
# real server, a canary that stays a canary, and the bytes a publish would ship. They need no
# device -- only the reference server and a client that verifies -- so they run here rather than
# waiting for hardware. `docs/keys.md` is the runbook they execute.
echo "==> rotating a signing key, publishing, and staging a canary"
"$HERE/../reference-server/rotation-drill.sh" "$HERE/build/rotation.conf" >/dev/null 2>&1 || status=1
"$HERE/../reference-server/cohort-drill.sh" "$HERE/build/cohort.conf" >/dev/null 2>&1 || status=1
# `publish-check.sh` takes named arguments, not an output path. This line used to pass one
# positional path, which the script rejects with "unknown argument" and exit 64 -- swallowed by the
# redirect, counted as a failure with no file behind it, so `P1`-`P7` were graded nowhere in this
# gate. Found 2026-09-17 while reading the script's own argument parser.
#
# The private keys are READ out of the guest's build file rather than restated here, the way
# `rotation-drill.sh` reads the public halves out of the trust anchor. A second copy of a key is a
# second thing to update, and `publish-check.sh` derives each public half from the private one it is
# given, so a transposed pair fails rather than passing.
GUEST_BUILD="$HERE/../../engine/samples/slice-guest/build.gradle.kts"
PUBLISH_SIGNING_KEY="$(grep -o 'val developmentSigningKey = "[0-9a-f]\{64\}"' "$GUEST_BUILD" | grep -o '[0-9a-f]\{64\}')"
PUBLISH_ROTATION_KEY="$(grep -o 'val rotationSigningKey = "[0-9a-f]\{64\}"' "$GUEST_BUILD" | grep -o '[0-9a-f]\{64\}')"
#
# The payload is REBUILT at the version being checked, and that is what makes `P1` and `P6` mean
# anything. `P1` is "the release identity is a build input" -- it catches a publishing step that
# stamps a version in afterwards, which invalidates the signature over it. Checking whatever version
# the payload already happened to carry would assert that a number equals itself. `P6` fails with
# it, because it looks for the client reaching `updated` at that same version.
#
# Rebuilt back to the default afterwards on every path, because the drills after this one use the
# payload on :8080 and a gate-stamped version left behind would follow them around.
PUBLISH_VERSION="gate-$(date +%Y%m%d%H%M%S)"
GUEST_DIR="$HERE/../../engine/samples/slice-guest/build/zipline/ProductionWebpack"
if [ -n "$PUBLISH_SIGNING_KEY" ]; then
  if "$HERE/../../engine/gradlew" -p "$HERE/../../engine" \
      :samples:slice-guest:jsBrowserProductionWebpackZipline \
      -PdogwoodVersion="$PUBLISH_VERSION" --max-workers=2 --console=plain -q >/dev/null 2>&1; then
    "$HERE/../reference-server/publish-check.sh" \
      --payload "$GUEST_DIR" \
      --version "$PUBLISH_VERSION" \
      --signing-key "$PUBLISH_SIGNING_KEY" \
      ${PUBLISH_ROTATION_KEY:+--rotation-key "$PUBLISH_ROTATION_KEY"} \
      --out "$HERE/build/publish-check.conf" >/dev/null 2>&1 || status=1
  else
    echo "    (the guest would not build at $PUBLISH_VERSION; P1-P7 not graded)" >&2
    status=1
  fi
  "$HERE/../../engine/gradlew" -p "$HERE/../../engine" \
    :samples:slice-guest:jsBrowserProductionWebpackZipline \
    --max-workers=2 --console=plain -q >/dev/null 2>&1 || status=1
else
  echo "    (no development signing key found in $GUEST_BUILD; P1-P7 not graded)" >&2
  status=1
fi

echo "==> web skew containment"
# Needs no device, so this one also runs in continuous integration (`conformance.yml`). It is kept
# here too because this script is the whole-matrix run, and a client graded in one place and not the
# other is how a matrix starts lying.
"$HERE/../skew-drill/run-web.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew-web.conf" ]; then
  grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/skew-web.conf" > "$HERE/build/web-skew.conf" || true
fi

echo "==> web accessibility"
"$HERE/run-web.sh" "$HERE/build/web-a11y.conf" >/dev/null 2>&1 || status=1

echo "==> ios accessibility and network policy"
CONF_OUT="$HERE/build/ios-a11y.conf" "$HERE/../a11y-drill/run.sh" >/dev/null 2>&1 || status=1

echo "==> the generated Material 3 tier on ios"
# `M1`-`M7`, and iOS only. Android's and the web's Material claims are already graded and must not
# be added here twice: `run-android.sh` above runs `connectedReleaseAndroidTest`, which runs every
# instrumented class in `slice-android` and `MaterialConformanceTest` is one of them, and
# `run-web.sh` below calls `web_material.py` as its third grader. iOS is the one client whose
# Material drill is a separate script, because VoiceOver has to be switched on first and the walk
# happens inside the application.
#
# It writes a log rather than a `.conf`, so the `CONF` lines are taken out of it here.
# `MaterialDrill.kt` prints its own `CONF RESULT client=ios`, so the file this produces declares its
# client the way every other drill's does.
#
# The log is deleted first. The drill only truncates it at the point it launches the application, so
# a run that refuses earlier -- no simulator, no payload on :8080 -- would otherwise leave yesterday's
# verdicts on disk for the grep below to harvest as though they were today's.
rm -f "$HERE/build/ios-material.log"
"$HERE/../a11y-drill/run-material.sh" "$HERE/build/ios-material.log" >/dev/null 2>&1 || status=1
if [ -f "$HERE/build/ios-material.log" ]; then
  tr -d '\r' < "$HERE/build/ios-material.log" \
    | grep -E "^CONF ([A-Z][0-9]|RESULT)" > "$HERE/build/ios-material.conf" || true
fi

echo "==> compatibility across the over-the-air gap"
# A committed payload fixture from an earlier toolchain, against a host built from current
# sources -- the pairing every deployment has and nothing else here exercises.
"$HERE/cross-version.sh" "$HERE/build/desktop-crossversion.conf" >/dev/null 2>&1 || status=1
# And on the two clients a product ships. Until the manifest address became overridable on Android
# and iOS, `K1`/`K2` could only be graded on the one client that had the switch.
for client in android ios; do
  "$HERE/cross-version-mobile.sh" "$client" "$HERE/build/${client}-crossversion.conf" \
    >/dev/null 2>&1 || status=1
done

echo "==> two engine versions meeting"
# `K3` and `K4`, and the pairing the drill above cannot reach. `cross-version.sh` serves a frozen,
# signed payload *artifact* to a host built today, which covers a payload that is merely old. This
# one builds both halves from source at two engine versions: a host checked out at the newest `v*`
# tag in a throwaway git worktree, and the payload built at `HEAD`, each served to the other. `K3`
# is the older host refusing today's payload; `K4` is today's host running the older payload, which
# is the direction a fleet actually lives in.
#
# It needs no device -- the desktop client is the instrument, because `-Ddogwood.manifest` already
# points it anywhere -- so it sits with the other deviceless drills rather than waiting for hardware.
#
# It refuses when there is no `v*` tag to compare against, and grades `K3` SKIP when the two
# versions declare the same dictionary: there is no skew to grade when the vocabulary did not move.
"$HERE/engine-skew.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/build/engine-skew.conf" ]; then
  # Renamed rather than left where it lands. This drill writes into the very directory the fold
  # reads, and the fold takes the client from `name.rsplit('-', 1)[0]` -- so `engine-skew.conf`
  # would invent a client called `engine` and carry `K3` and `K4` out of the desktop column with it.
  grep -E "^CONF ([A-Z][0-9]|RESULT)" "$HERE/build/engine-skew.conf" \
    > "$HERE/build/desktop-engineskew.conf" || true
  rm -f "$HERE/build/engine-skew.conf"
fi

echo "==> performance budgets"
python3 "$HERE/from_phase0.py" > "$HERE/build/phase0.raw" || status=1
split_by_client "$HERE/build/phase0.raw" "-perf"
python3 "$HERE/from_web_weight.py" > "$HERE/build/web-perf.conf" || status=1
# The guest script is a separate download from the page that fetches it, and until `G6` nothing
# bounded it -- the Material catalogue grew the guest by a quarter of a megabyte under a budget
# that only ever looked at the host. Appended rather than a second file, because both are the same
# client's bytes.
python3 "$HERE/from_guest_weight.py" >> "$HERE/build/web-perf.conf" || status=1

# Fold every per-drill file into its client's, so the aggregator sees one run per client.
python3 - "$HERE/build" <<'PY'
import glob, pathlib, sys
out = pathlib.Path(sys.argv[1])
for extra in sorted(glob.glob(str(out / '*-*.conf'))):
    name = pathlib.Path(extra).name[:-len('.conf')]
    client = name.rsplit('-', 1)[0]
    target = out / f'{client}.conf'
    lines = pathlib.Path(extra).read_text().splitlines()
    body = [l for l in lines if not l.startswith('CONF RESULT')]
    if target.exists():
        existing = target.read_text().splitlines()
        keep = [l for l in existing if not l.startswith('CONF RESULT')]
        # Deduplicated by claim: a duplicated verdict is harmless to the aggregator and misleading
        # to a person reading the file.
        seen = {l.split(' -- ')[0] for l in keep if l.startswith('CONF ')}
        body = [l for l in body if l.split(' -- ')[0] not in seen]
        target.write_text('\n'.join(
            keep + body + [l for l in existing if l.startswith('CONF RESULT')]) + '\n')
    else:
        target.write_text('\n'.join(lines) + '\n')
    pathlib.Path(extra).unlink()
PY

echo
# --- Part 3 of the plan, written by the run that produced it -----------------------------------
#
# `plans/conformance.md` says that table is generated and never hand-maintained, and until now this
# gate did not do the generating: it printed the matrix and a person pasted it across. That is how
# Part 3 came to be dated `2026-09-14` while describing a smaller project than the one in the
# repository. `--update-plan` writes between the two `conformance-matrix` markers and refuses if
# they are not both there.
#
# **The guard is a coverage test, not a verdict test.** A partial run -- no simulator, no emulator,
# a drill that refused for want of a served payload -- grades fewer claims, and writing that back
# would *delete* evidence rather than refresh it. A client nothing reached has no column at all, and
# a drill that refused leaves its claims absent, which renders as `—`, a gap. The plan's own rule is
# that a generated matrix must never invent a gap.
#
# So the rule is measured against the table being replaced rather than against a threshold somebody
# chose: **this run must have graded every client the committed matrix names, and at least as many
# claims for each of them as that matrix records.** The legitimate gaps survive it -- `K1` on web
# and `H5` on android are gaps today and stay gaps, because neither changes a count. A partial run
# does not: a client nothing reached fails the first half, a drill that refused fails the second.
#
# A red cell is not a reason to refuse the write. A failing claim recorded in the matrix is the
# table doing its job; only a run that knows *less* than the table it would overwrite is turned
# away. Tier C passes `--update-plan` unconditionally and correctly: its fold step already dies
# rather than produce a run it cannot attribute, and it uploads the result instead of committing it.
#
#   SKIP_MATRIX_WRITE=1   never write. For a deliberately partial run -- grading one client while
#                         working on it -- where the console matrix is wanted and the file must not
#                         move.
#   FORCE_MATRIX_WRITE=1  write anyway. For the one honest case the guard cannot tell apart from a
#                         partial run: a change that legitimately retires claims.
PLAN="$HERE/../../plans/conformance.md"
matrix_args=()
if [ "${SKIP_MATRIX_WRITE:-0}" = "1" ]; then
  echo "(Part 3 not written: SKIP_MATRIX_WRITE=1)" >&2
else
  if [ "${FORCE_MATRIX_WRITE:-0}" = "1" ]; then
    write_back=0
    echo "(the write-back guard was overridden: FORCE_MATRIX_WRITE=1)" >&2
  else
    python3 - "$PLAN" "$HERE" "$HERE"/build/*.conf <<'PY'
import pathlib, re, sys
plan_path, here = sys.argv[1], sys.argv[2]
sys.path.insert(0, here)
import aggregate

text = pathlib.Path(plan_path).read_text()
if aggregate.BEGIN not in text or aggregate.END not in text:
    sys.exit('the plan has no conformance-matrix markers; aggregate.py would refuse the write anyway')
block = text[text.index(aggregate.BEGIN):text.index(aggregate.END)]

# The counts are read off the block's own footer -- `- **android**: pass 65, skip 4` -- which
# `render()` writes from `len(runs[client])`, so it is the same measure as the one taken below. A
# block with no footer is one nothing generated, and there is nothing there to protect.
was = {}
for line in re.finditer(r'^- \*\*(\w+)\*\*: (.+)$', block, re.M):
    was[line.group(1)] = sum(int(n) for n in re.findall(r'\d+', line.group(2)))
if not was:
    sys.exit(0)

runs = {}
for path in sys.argv[3:]:
    parsed = aggregate.parse(path)
    if not parsed:
        continue  # aggregate.py itself reports this file, loudly, a moment from now
    for client, claims in parsed.items():
        aggregate.merge(runs, client, claims)

problems = []
for client in sorted(was):
    if client not in runs:
        problems.append(f'{client}: the committed matrix has a column and this run graded nothing')
    elif len(runs[client]) < was[client]:
        problems.append(f'{client}: {len(runs[client])} claims this run, '
                        f'against {was[client]} in the committed matrix')
if problems:
    print('Part 3 was NOT written: this run knows less than the table it would replace.',
          file=sys.stderr)
    for problem in problems:
        print(f'  {problem}', file=sys.stderr)
    print('  Run the whole gate with every device attached, or SKIP_MATRIX_WRITE=1 to say so '
          'deliberately, or FORCE_MATRIX_WRITE=1 if this run really does retire claims.',
          file=sys.stderr)
    sys.exit(1)
PY
    write_back=$?
  fi
  if [ "$write_back" = "0" ]; then
    # The banner names the machine and the commit, because a generated table whose provenance is a
    # date is one nobody can place: the same date covers a run with an emulator attached and one
    # without, and a clean tree and a dirty one.
    root="$(cd "$HERE/../.." && pwd)"
    commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo 'an unknown commit')"
    branch="$(git -C "$root" rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'an unknown branch')"
    if [ -n "$(git -C "$root" status --porcelain 2>/dev/null)" ]; then
      tree=', with uncommitted changes in the working tree'
    else
      tree=''
    fi
    if [ "${SKIP_ENGINE_BUILD:-0}" = "1" ]; then
      engine=', with `SKIP_ENGINE_BUILD=1` grading the test results already on disk'
    else
      engine=''
    fi
    matrix_args=(--update-plan "$PLAN" --provenance \
      "Generated $(date +%Y-%m-%d) by \`tools/conformance/run-all.sh\` on \`$(hostname -s)\` \
($(uname -sm)), from \`$commit\` on branch \`$branch\`${tree}${engine}.")
  else
    # A gate that could not write the matrix is a gate that did not run whole, and a whole run is
    # what this script's one line of output is asked about. Reported as its own verdict below rather
    # than as a red cell, because it is neither -- nothing failed, something was never graded.
    partial=1
    status=1
  fi
fi

python3 "$HERE/aggregate.py" "${matrix_args[@]+"${matrix_args[@]}"}" "$HERE"/build/*.conf || status=1

echo
if [ "$status" = "0" ] && [ "${SKIP_MATRIX_WRITE:-0}" = "1" ]; then
  echo "PASS -- every claim this machine can grade is met; Part 3 was left alone by request"
elif [ "$status" = "0" ]; then
  echo "PASS -- every claim this machine can grade is met, and Part 3 of plans/conformance.md"
  echo "        has been rewritten from this run; commit it with the change it grades"
elif [ "${partial:-0}" = "1" ]; then
  echo "FAIL -- this run graded less than the matrix it would have replaced, so Part 3 was not" >&2
  echo "        written; the refusal above says which client, and the matrix above says what" >&2
  echo "        this run did reach" >&2
else
  echo "FAIL -- see the matrix above; a red cell blocks the merge" >&2
fi
exit "$status"
