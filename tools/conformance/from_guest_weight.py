#!/usr/bin/env python3
"""Project Dogwood -- the guest payload's weight, as a conformance verdict.

`from_web_weight.py`'s sibling, and the reason there are two.

`G5` bounds what a visitor downloads to get a **host**: the WebAssembly modules, `app.js` and
`index.html`. It deliberately does not count `guest-kotlin.js`, because that file is not part of
the host build at all -- it is the Over-The-Air (OTA) payload, rebuilt and republished without
touching the client, which is the whole point of this architecture. Counting it inside `G5` would
have made a payload change look like a client regression and a client regression look like a
payload change.

The consequence of leaving it out was that **nothing bounded it**. The generated Material 3 tier
put a large vocabulary into the guest as well as into the host, and the host half was caught by
`G5` while the guest half grew unobserved. `G6` is that gap closed: the same brotli measurement,
the same ceiling-with-headroom rule, the same attribution requirement on a raise
(`budgets.tsv`).

It matters more than its share of the bytes suggests, because the guest is fetched on a different
schedule from the host. The host is downloaded once and cached behind an immutable content hash;
the guest is fetched whenever a release is published, which is as often as a product ships. A
kilobyte in the host is paid once per visitor and a kilobyte in the guest is paid once per visitor
per release.

Brotli at quality 11, matching ADR-030's table and `from_web_weight.py`, so the two numbers are
comparable and can be added.
"""
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
DIST = HERE / '../../engine/samples/web-slice/build/dist/wasmJs/productionExecutable'
GUEST = 'guest-kotlin.js'


def budget() -> int:
    for line in (HERE / 'budgets.tsv').read_text().splitlines():
        if line.startswith('G6\t'):
            return int(line.split('\t')[1])
    raise SystemExit('no G6 budget in budgets.tsv')


def brotli_size(path: pathlib.Path) -> int:
    """Compressed size, from the `brotli` binary. Quality 11, matching ADR-030's table."""
    out = subprocess.run(['brotli', '-q', '11', '-c', str(path)], capture_output=True)
    if out.returncode != 0:
        raise FileNotFoundError('brotli failed')
    return len(out.stdout)


def measure() -> tuple[int, int] | None:
    script = (DIST / GUEST).resolve()
    if not script.is_file():
        return None
    try:
        return brotli_size(script), script.stat().st_size
    except FileNotFoundError:
        return None


limit = budget()
measured = measure()
if measured is None:
    # A failure, not a skip, for the reason `from_web_weight.py` states at the same point: this
    # grader ran as a SKIP once in continuous integration because `brotli` was absent, and the
    # workflow went green having graded nothing. An environment asked to grade a budget and unable
    # to is broken, and saying so is the only way that gets fixed.
    print(f'CONF G6 FAIL -- no measurement. Is the web-slice distribution built '
          f'(`:samples:web-slice:wasmJsBrowserDistribution`), does it contain {GUEST}, '
          f'and is `brotli` on PATH?')
    print('CONF RESULT client=web passed=0 failed=1 skipped=0')
    sys.exit(1)

total, raw = measured
detail = f'{GUEST} {total:,} bytes brotli of {limit:,} ({raw:,} uncompressed)'
if total <= limit:
    print(f'CONF G6 PASS -- {detail}')
    print('CONF RESULT client=web passed=1 failed=0 skipped=0')
    sys.exit(0)
print(f'CONF G6 FAIL -- {detail}. The guest is re-fetched on every release rather than cached '
      f'behind an immutable hash like the host, so this is a cost paid per visitor per publish. '
      f'A raise needs the three builds `budgets.tsv` requires.')
print('CONF RESULT client=web passed=0 failed=1 skipped=0')
sys.exit(1)
