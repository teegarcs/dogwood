#!/usr/bin/env python3
"""Project Dogwood -- turn the Phase 0 numbers into conformance verdicts.

`tools/phase0` measures four things per host and writes them as JavaScript Object Notation. Until
now nothing compared them to the budgets the roadmap sets, so they were numbers in a file: a
measurement is not a gate, and a measurement nobody compares to a threshold is a number nobody
reads. That is the same shape as the evidence-nobody-consumes failure this project has met
repeatedly.

Budgets live in `budgets.tsv`. What each claim reads is fixed here, because a budget is only
meaningful against a stated statistic -- "recomposition is fine" means nothing without "95th
percentile of the reference screen".

**Gate validity is carried, not dropped, and the rule is deliberately asymmetric.** The Phase 0
gate is defined on a low-end 2022-tier Android device, which this project decided not to acquire
(Layer 4 ADR-008). **No host that exists here is gate-valid** -- not the development machine, not
the simulator, and not the Pixel 10 Pro either, which is a flagship where the gate names an entry
tier. So:

  - **Within budget on a non-gate-valid host is a `SKIP`, never a `PASS`.** A comfortable number
    from a fast machine says nothing about a slow one, and letting it read green would quietly
    discharge a risk the project chose to carry.
  - **Over budget on a non-gate-valid host is a `FAIL`.** The asymmetry is the point: favourable
    hardware exceeding the budget is damning precisely because the hardware was favourable. That
    also keeps these numbers useful as regression detection, which is the thing they can honestly
    do today.
"""
import glob
import json
import sys

HERE = __file__.rsplit('/', 1)[0]

# Which host each results file speaks for. The Phase 0 harness names its runs by machine.
CLIENTS = {
    'google-pixel-10-pro': 'android',
    'google-sdk-gphone64-arm64': 'android',
    # Desktop is deliberately absent. It is a development loop rather than a shipping target, so
    # it is exempt from group G (`exempt.tsv`) -- and emitting skips for a client that is not
    # graded turns an exemption into a gap in the matrix. Its numbers are still written by the
    # Phase 0 harness and are useful for comparison; they are just not claims.
    'ios-17-5-simulator-not-gate-valid': 'ios',
}

# Nothing here is gate-valid; the set is written out rather than inferred from a slug so that
# adding the low-end device one day is a deliberate edit to this line.
GATE_VALID: set[str] = set()


def load_budgets():
    budgets = {}
    with open(f'{HERE}/budgets.tsv') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                claim, ms, what = line.split('\t')
                budgets[claim] = (float(ms), what)
    return budgets


def measurements(report):
    """The four numbers, each from the statistic its budget is stated against."""
    out = {}
    rows = report.get('experiment02') or []
    if rows:
        out['G1'] = rows[0]['recomposeOneNodeDiff']['p95Ms']
    points = (report.get('experiment03') or {}).get('points') or []
    steady = next((p for p in points if p['changes'] == 1), None)
    if steady:
        # The per-frame reading, ruled in the Phase 0 appendix: the 4 ms bounds a tap's crossing,
        # and a tap does not produce a whole screen. The per-screen figure belongs to cold start.
        out['G2'] = steady['crossZiplineSerialized']['p50Ms']
    gc = (report.get('experiment04') or {}).get('points') or []
    if gc:
        out['G3'] = max(p['recomposeUnderLoad']['p99Ms'] for p in gc)
    cold = (report.get('experiment01') or {}).get('coldStartToFirstComposition')
    if cold:
        out['G4'] = cold['p50Ms']
    return out


def main():
    budgets = load_budgets()
    by_client = {}
    for path in glob.glob(f'{HERE}/../phase0/results/*.json'):
        slug = path.rsplit('/', 1)[-1][:-len('.json')]
        if slug.startswith('alloc-gc-'):
            continue
        client = CLIENTS.get(slug)
        if client is None:
            continue
        report = json.load(open(path))
        label = report.get('label', slug)
        gate_valid = slug in GATE_VALID
        for claim, value in measurements(report).items():
            budget, what = budgets[claim]
            entry = by_client.setdefault(client, {})
            # When two hosts speak for one client, the slower one is the better witness: an
            # emulator on a development machine runs on that machine's processor, so a phone's
            # number is closer to what a user meets. Highest measurement wins.
            if claim in entry and entry[claim][1] >= value:
                continue
            entry[claim] = (gate_valid, value, budget, what, label)

    failures = 0
    for client in sorted(by_client):
        passed = failed = skipped = 0
        for claim in sorted(by_client[client]):
            gate_valid, value, budget, what, label = by_client[client][claim]
            shown = f'{value:.2f} ms of {budget} ms ({what}), on {label}'
            if not gate_valid and value > budget:
                failed += 1
                failures += 1
                print(f'CONF {claim} FAIL -- {shown}; over budget on hardware more favourable '
                      f'than the gate device, which makes it worse rather than excusable')
            elif not gate_valid:
                skipped += 1
                print(f'CONF {claim} SKIP -- {shown}; within budget but not gate-valid hardware, '
                      f'so it bounds expectations rather than closing the gate')
            elif value <= budget:
                passed += 1
                print(f'CONF {claim} PASS -- {shown}')
            else:
                failed += 1
                failures += 1
                print(f'CONF {claim} FAIL -- {shown}')
        print(f'CONF RESULT client={client} passed={passed} failed={failed} skipped={skipped}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
