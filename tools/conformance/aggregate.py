#!/usr/bin/env python3
"""Project Dogwood -- the conformance matrix, generated from real runs.

`plans/conformance.md` grades four clients against one capability list. That table is **generated**
and never hand-maintained, for a reason the plan states: a matrix that drifts from reality is worse
than no matrix, because it is a document asserting that something is tested.

Input is one `.conf` file per client -- the `CONF` lines each drill emits. Output is a markdown
table, plus a non-zero exit if any client reported a failure.

    tools/conformance/aggregate.py build/*.conf
"""
import re
import sys
from collections import OrderedDict

CLAIM = re.compile(r'^CONF ([A-G]\d+(?:-[a-z]+)?) (PASS|FAIL|SKIP|KNOWN)(?: -- (.*))?$')
RESULT = re.compile(r'^CONF RESULT client=(\w+) (.*)$')

MARK = {'PASS': '✅', 'FAIL': '❌', 'SKIP': '·', 'KNOWN': '⚠️'}


def parse(path):
    client, claims = None, OrderedDict()
    with open(path) as f:
        for raw in f:
            line = raw.strip().lstrip('﻿')
            result = RESULT.match(line)
            if result:
                client = result.group(1)
                continue
            claim = CLAIM.match(line)
            if claim:
                claims[claim.group(1)] = (claim.group(2), claim.group(3) or '')
    return client, claims


def main(paths):
    runs = {}
    for path in paths:
        client, claims = parse(path)
        if client is None:
            print(f'{path}: no CONF RESULT line, so the client is unknown', file=sys.stderr)
            return 2
        runs[client] = claims

    # Base claims only in the columns: `D4-reverse` and friends are evidence for `D4`, not separate
    # promises, and listing them as rows would make one claim look like three.
    base = OrderedDict()
    for claims in runs.values():
        for claim_id in claims:
            base.setdefault(claim_id.split('-')[0], None)

    clients = sorted(runs)
    print('| Claim | ' + ' | '.join(clients) + ' |')
    print('|---' * (len(clients) + 1) + '|')
    failures = 0
    stale = []
    for claim_id in base:
        cells = []
        for client in clients:
            related = {k: v for k, v in runs[client].items() if k.split('-')[0] == claim_id}
            if not related:
                cells.append('—')
                continue
            verdicts = {v[0] for v in related.values()}
            # Worst verdict wins: a claim with one failing sub-check has not been met.
            for verdict in ('FAIL', 'KNOWN', 'SKIP', 'PASS'):
                if verdict in verdicts:
                    cells.append(MARK[verdict])
                    if verdict == 'FAIL':
                        failures += 1
                    break
        print(f'| {claim_id} | ' + ' | '.join(cells) + ' |')

    print()
    print('✅ met · ⚠️ known gap, not blocking · · not applicable here · ❌ failed · — not run')
    print()
    for client in clients:
        counts = {}
        for verdict, _ in runs[client].values():
            counts[verdict] = counts.get(verdict, 0) + 1
        print(f'- **{client}**: ' + ', '.join(f'{v.lower()} {n}' for v, n in sorted(counts.items())))

    # A known gap that has started passing means the entry is stale -- worth saying, because a
    # stale allowance quietly excuses a claim nobody is checking any more.
    for client, claims in runs.items():
        for claim_id, (verdict, detail) in claims.items():
            if verdict == 'KNOWN':
                stale.append(f'{client}:{claim_id}')
    if stale:
        print()
        print('Known gaps, which are failures this project has chosen not to block on: '
              + ', '.join(stale) + '. Each must be listed in `plans/conformance.md` with a cause.')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
