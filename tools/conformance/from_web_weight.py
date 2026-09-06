#!/usr/bin/env python3
"""Project Dogwood -- turn the web page's weight into a conformance verdict.

ADR-030 measured the page at 2.92 MB brotli and ADR-038 showed the wait is transfer-bound about a
hundred to one, so page weight is the only lever the web profile has. A number nobody compares to a
threshold does not act as a lever, which is what this closes.

The budget is deliberately a **ceiling above today's figure**, not today's figure: 3.30 MB against
2.92 MB. Pinning it exactly would fail on the next legitimate component and teach everyone to raise
the number, which is how a budget stops meaning anything. The headroom is roughly one Material 3
(~0.15 MB) plus room to be wrong, and passing it should prompt a conversation rather than a bump.
"""
import subprocess
import sys

HERE = __file__.rsplit('/', 1)[0]


def budget() -> int:
    with open(f'{HERE}/budgets.tsv') as f:
        for line in f:
            if line.startswith('G5\t'):
                return int(line.split('\t')[1])
    raise SystemExit('no G5 budget')


def measure() -> int | None:
    """Brotli bytes for the shipped distribution, from the harness that already computes them."""
    result = subprocess.run(
        [f'{HERE}/../web-weight/measure.sh'], capture_output=True, text=True,
    )
    for line in reversed(result.stdout.splitlines()):
        if line.strip().startswith('TOTAL'):
            return int(line.split('brotli')[1].strip())
    return None


total = measure()
limit = budget()
if total is None:
    # A failure, not a skip. This ran as a SKIP once, in continuous integration, because `brotli`
    # was not installed -- and the workflow went green having graded nothing. An environment asked
    # to grade a budget and unable to is a broken environment, and saying so is the only way that
    # gets fixed rather than tolerated.
    print('CONF G5 FAIL -- the page-weight harness produced no total. Is the distribution built, '
          'and is `brotli` on PATH?')
    print('CONF RESULT client=web passed=0 failed=1 skipped=0')
    sys.exit(1)

detail = f'{total:,} bytes brotli of {limit:,}'
if total <= limit:
    print(f'CONF G5 PASS -- {detail}')
    print('CONF RESULT client=web passed=1 failed=0 skipped=0')
    sys.exit(0)
print(f'CONF G5 FAIL -- {detail}; transfer dominates first frame about a hundred to one '
      f'(ADR-038), so this is a wait, not a download')
print('CONF RESULT client=web passed=0 failed=1 skipped=0')
sys.exit(1)
