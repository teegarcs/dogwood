#!/usr/bin/env python3
"""Project Dogwood -- the conformance matrix, generated from real runs.

`plans/conformance.md` grades four clients against one capability list. That table is **generated**
and never hand-maintained, for a reason the plan states: a matrix that drifts from reality is worse
than no matrix, because it is a document asserting that something is tested.

Input is the `.conf` files the drills emit -- any number of them, in any order, several per client.
Output is a markdown table, plus a non-zero exit if any client reported a failure.

    tools/conformance/aggregate.py build/*.conf
    tools/conformance/aggregate.py --update-plan plans/conformance.md build/*.conf

`--update-plan` writes the same table back into Part 3 of the plan, between the two
`conformance-matrix` comment markers. It exists because the plan says that table is generated and
never hand-maintained, and until the nightly workflow there was nothing that actually did the
writing -- a person copied the output across, which is how it went stale twice.
"""
import datetime
import pathlib
import re
import sys
from collections import OrderedDict

BEGIN = '<!-- conformance-matrix:begin -->'
END = '<!-- conformance-matrix:end -->'

# `[A-Z]`, not `[A-G]`. The catalogue was seven groups when this was written and the eighth was
# added the moment release control existed; a pattern that names the groups it happens to know
# **silently drops** the rest, which is the exact shape of failure this whole harness exists to
# catch — a claim that is graded, reports PASS, and is counted by nobody.
CLAIM = re.compile(r'^CONF ([A-Z]\d+(?:-[a-z]+)?) (PASS|FAIL|SKIP)(?: -- (.*))?$')
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


# Worst verdict first. Used when two drills grade the same claim on the same client -- see
# `merge` -- so a red cell cannot be painted green by whichever file the shell happened to glob
# second.
WORST = ('FAIL', 'SKIP', 'PASS')


def parse(path):
    """Every run in one file, as `{client: {claim: (verdict, detail)}}`.

    A file can hold more than one run. `run-web.sh` appends three graders into one file and each
    prints its own `CONF RESULT`, and the shared-test grader emits every client in sequence. So
    claims are attributed to the `RESULT` line that **follows** them, which is the order every
    drill here writes: grade, then declare.

    Claims after the last `RESULT` line attach to the client that line named. That is not a
    fallback -- `tools/a11y-drill/run.sh` appends the iOS network-policy claims after the
    in-application drill has already declared its result, and they belong to the same client.
    """
    out, pending, client = OrderedDict(), OrderedDict(), None
    with open(path) as f:
        for raw in f:
            line = raw.strip().lstrip('\ufeff')
            result = RESULT.match(line)
            if result:
                client = result.group(1)
                out.setdefault(client, OrderedDict()).update(pending)
                pending = OrderedDict()
                continue
            claim = CLAIM.match(line)
            if claim:
                pending[claim.group(1)] = (claim.group(2), claim.group(3) or '')
    if pending:
        if client is None:
            return None
        out[client].update(pending)
    return out


def merge(runs, client, claims):
    """Fold one drill's claims into a client's, worst verdict winning.

    One client is graded by several drills -- accessibility, Material, skew, pre-flight,
    cross-version, budgets -- and each writes its own file. Before this, the last file read simply
    replaced the ones before it, and `run-all.sh` worked around that by folding the files together
    itself before calling this script. A caller that does not know to do that -- the nightly
    workflow, for one -- would have had a matrix built from whichever drill sorted last, with every
    other drill's claims silently absent. Absent reads as `--`, a gap, which is the one thing a
    generated matrix must never invent.
    """
    into = runs.setdefault(client, OrderedDict())
    for claim_id, value in claims.items():
        existing = into.get(claim_id)
        if existing is None or WORST.index(value[0]) < WORST.index(existing[0]):
            into[claim_id] = value


def render(runs):
    """The matrix, as lines. Returns `(lines, failures)`."""
    out = []
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

    out.append('| Claim | ' + ' | '.join(clients) + ' |')
    out.append('|---' * (len(clients) + 1) + '|')
    failures = 0
    for claim_id in base:
        cells = []
        for client in clients:
            related = {k: v for k, v in runs[client].items() if k.split('-')[0] == claim_id}
            # An exemption outranks whatever a drill said about the claim. It has to: a drill that
            # walks a screen and finds the platform cannot express the thing has *something* to
            # report, and reporting it as `·` -- "nothing here to judge" -- is the one reading that
            # is wrong. There is something to judge and a decision was taken about it; `n/a` sends
            # the reader to `exempt.tsv`, where the reason is.
            if exempt(client, claim_id):
                cells.append('n/a')
                continue
            if not related:
                cells.append('—')
                continue
            verdicts = {v[0] for v in related.values()}
            # Worst verdict wins: a claim with one failing sub-check has not been met.
            for verdict in ('FAIL', 'SKIP', 'PASS'):
                if verdict in verdicts:
                    cells.append(MARK[verdict])
                    if verdict == 'FAIL':
                        failures += 1
                    break
        out.append(f'| {claim_id} | ' + ' | '.join(cells) + ' |')

    out.append('')
    out.append('✅ met · · nothing here to judge · n/a exempt, see `exempt.tsv` · ❌ failed · — gap')
    if exemptions:
        out.append('')
        for client, prefix, reason in exemptions:
            out.append(f'- `{client}` is not graded on {prefix}: {reason}')
    out.append('')
    for client in clients:
        counts = {}
        for verdict, _ in runs[client].values():
            counts[verdict] = counts.get(verdict, 0) + 1
        out.append(f'- **{client}**: '
                   + ', '.join(f'{v.lower()} {n}' for v, n in sorted(counts.items())))
    return out, failures


def update_plan(path, lines, provenance):
    """Replace the marked block in the plan, and refuse rather than guess if it is not there.

    Refusing matters more than it looks. The alternative -- appending, or matching the table by its
    shape -- would let a renamed section or a moved marker produce a document with two matrices in
    it, one of them stale, which is worse than the drift this is meant to stop.
    """
    doc = pathlib.Path(path)
    text = doc.read_text()
    if BEGIN not in text or END not in text:
        raise SystemExit(f'{path} has no {BEGIN} / {END} pair to write between')
    before = text[:text.index(BEGIN) + len(BEGIN)]
    after = text[text.index(END):]
    stamp = provenance or f'Generated {datetime.date.today().isoformat()}.'
    doc.write_text(before + '\n' + f'*{stamp}*' + '\n\n' + '\n'.join(lines) + '\n\n' + after)


def main(argv):
    plan, provenance, paths = None, None, []
    rest = list(argv)
    while rest:
        arg = rest.pop(0)
        if arg == '--update-plan':
            plan = rest.pop(0)
        elif arg == '--provenance':
            provenance = rest.pop(0)
        else:
            paths.append(arg)
    if not paths:
        print('usage: aggregate.py [--update-plan PLAN] [--provenance TEXT] RESULT.conf...',
              file=sys.stderr)
        return 2

    runs = {}
    for path in paths:
        parsed = parse(path)
        if not parsed:
            print(f'{path}: no CONF RESULT line, so the client is unknown', file=sys.stderr)
            return 2
        for client, claims in parsed.items():
            merge(runs, client, claims)

    lines, failures = render(runs)
    print('\n'.join(lines))
    if plan:
        update_plan(plan, lines, provenance)
        print(f'\nwrote the matrix into {plan}', file=sys.stderr)

    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
