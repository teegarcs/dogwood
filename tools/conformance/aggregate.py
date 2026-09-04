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

CLAIM = re.compile(r'^CONF ([A-G]\d+(?:-[a-z]+)?) (PASS|FAIL|SKIP)(?: -- (.*))?$')
RESULT = re.compile(r'^CONF RESULT client=(\w+) (.*)$')

MARK = {'PASS': '✅', 'FAIL': '❌', 'SKIP': '·'}

HERE = __file__.rsplit('/', 1)[0]


def load_exemptions():
    """Claims a client is deliberately not graded on, with a reason.

    Rendered `n/a` rather than as a gap, so an empty cell is a decision somebody can point at
    instead of work nobody did.
    """
    out = []
    try:
        with open(f'{HERE}/exempt.tsv') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#'):
                    client, prefix, reason = line.split('\t')
                    out.append((client, prefix, reason))
    except FileNotFoundError:
        pass
    return out


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

    clients = sorted(runs)
    exemptions = load_exemptions()

    def exempt(client, claim_id):
        for c, prefix, reason in exemptions:
            if c == client and claim_id.startswith(prefix):
                return reason
        return None

    # Base claims only in the columns: `D4-reverse` and friends are evidence for `D4`, not separate
    # promises, and listing them as rows would make one claim look like three.
    base = OrderedDict()
    for claims in runs.values():
        for claim_id in claims:
            base.setdefault(claim_id.split('-')[0], None)

    print('| Claim | ' + ' | '.join(clients) + ' |')
    print('|---' * (len(clients) + 1) + '|')
    failures = 0
    for claim_id in base:
        cells = []
        for client in clients:
            related = {k: v for k, v in runs[client].items() if k.split('-')[0] == claim_id}
            if not related:
                cells.append('n/a' if exempt(client, claim_id) else '—')
                continue
            verdicts = {v[0] for v in related.values()}
            # Worst verdict wins: a claim with one failing sub-check has not been met.
            for verdict in ('FAIL', 'SKIP', 'PASS'):
                if verdict in verdicts:
                    cells.append(MARK[verdict])
                    if verdict == 'FAIL':
                        failures += 1
                    break
        print(f'| {claim_id} | ' + ' | '.join(cells) + ' |')

    print()
    print('✅ met · · nothing here to judge · n/a exempt, see `exempt.tsv` · ❌ failed · — gap')
    if exemptions:
        print()
        for client, prefix, reason in exemptions:
            print(f'- `{client}` is not graded on {prefix}: {reason}')
    print()
    for client in clients:
        counts = {}
        for verdict, _ in runs[client].values():
            counts[verdict] = counts.get(verdict, 0) + 1
        print(f'- **{client}**: ' + ', '.join(f'{v.lower()} {n}' for v, n in sorted(counts.items())))

    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
