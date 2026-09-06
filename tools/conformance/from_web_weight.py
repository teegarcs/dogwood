#!/usr/bin/env python3
"""Project Dogwood -- the shipped web page's weight, as a conformance verdict.

ADR-030 measured what a Compose Multiplatform page weighs and ADR-038 showed the wait is
transfer-bound about a hundred to one, so page weight is the only lever the web profile has. A
number nobody compares to a threshold is not a lever, which is what this closes.

**It measures the shipped web slice, and the first version did not.** It called
`tools/web-weight/measure.sh`, which measures the two *spike* modules that ADR-030 built to
establish what Compose costs -- artifacts no product change can affect. So it reported a steady
2.92 MB through a change that grew the real page by 463 kilobytes, and it reported that steadiness
as evidence the change was free. Measuring the thing under discussion rather than a proxy for it is
the whole of the fix.

The source map is excluded deliberately, on the same rule `measure.sh` uses: browsers fetch it only
when developer tools are open, so counting it would overstate what a visitor downloads.
"""
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
DIST = HERE / '../../engine/samples/web-slice/build/dist/wasmJs/productionExecutable'


def budget() -> int:
    for line in (HERE / 'budgets.tsv').read_text().splitlines():
        if line.startswith('G5\t'):
            return int(line.split('\t')[1])
    raise SystemExit('no G5 budget in budgets.tsv')


def brotli_size(path: pathlib.Path) -> int:
    """Compressed size, from the `brotli` binary. Quality 11, matching ADR-030's table."""
    out = subprocess.run(['brotli', '-q', '11', '-c', str(path)], capture_output=True)
    if out.returncode != 0:
        raise FileNotFoundError('brotli failed')
    return len(out.stdout)


def measure() -> tuple[int, list[str]] | None:
    dist = DIST.resolve()
    if not dist.is_dir():
        return None
    wanted = sorted(dist.glob('*.wasm')) + [dist / 'app.js', dist / 'index.html']
    files = [f for f in wanted if f.is_file()]
    if not files:
        return None
    try:
        sizes = [(f.name, brotli_size(f)) for f in files]
    except FileNotFoundError:
        return None
    return sum(s for _, s in sizes), [f'{n} {s:,}' for n, s in sizes]


limit = budget()
measured = measure()
if measured is None:
    # A failure, not a skip. This ran as a SKIP once in continuous integration, because `brotli`
    # was absent, and the workflow went green having graded nothing. An environment asked to grade
    # a budget and unable to is broken, and saying so is the only way that gets fixed.
    print('CONF G5 FAIL -- no measurement. Is the web-slice distribution built '
          '(`:samples:web-slice:wasmJsBrowserDistribution`) and is `brotli` on PATH?')
    print('CONF RESULT client=web passed=0 failed=1 skipped=0')
    sys.exit(1)

total, breakdown = measured
detail = f'{total:,} bytes brotli of {limit:,} ({"; ".join(breakdown)})'
if total <= limit:
    print(f'CONF G5 PASS -- {detail}')
    print('CONF RESULT client=web passed=1 failed=0 skipped=0')
    sys.exit(0)
print(f'CONF G5 FAIL -- {detail}. Transfer dominates first frame about a hundred to one '
      f'(ADR-038), so this is a wait rather than a download.')
print('CONF RESULT client=web passed=0 failed=1 skipped=0')
sys.exit(1)
