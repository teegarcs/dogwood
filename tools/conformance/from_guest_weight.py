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
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
DIST = HERE / '../../engine/samples/web-slice/build/dist/wasmJs/productionExecutable'

# The guest script is CONTENT-ADDRESSED since ADR-078: `guest-kotlin-<first 16 hex of its
# SHA-256>.js`, so that two live releases name two different scripts rather than one. Its name
# therefore changes whenever the payload does, and a budget that looked it up by a fixed string
# would stop finding it on the next guest build -- reporting a pass having measured nothing, which
# is the exact failure this file's comment below already exists about.
#
# The plain name is still matched: `signWebSidecars` is what does the addressing, and a
# distribution assembled without it (a raw `wasmJsBrowserDistribution` from an older tree, or a
# directory a drill is halfway through rewriting) still ships one.
GUEST = 'guest-kotlin[-<16 hex>].js'
GUEST_PATTERN = re.compile(r'guest-kotlin(-[0-9a-f]{16})?\.js')


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


def locate() -> pathlib.Path | None:
    """The one guest script in the distribution, or nothing.

    Nothing rather than a guess when there are several. Two content addresses in one directory
    means a build step left a stale payload behind, and picking one of them would measure a file
    the distribution may not even serve -- a wrong number reported confidently, which is worse
    than the refusal below.
    """
    if not DIST.is_dir():
        return None
    found = sorted(p for p in DIST.iterdir() if GUEST_PATTERN.fullmatch(p.name))
    return found[0] if len(found) == 1 else None


def measure() -> tuple[int, int, str] | None:
    script = locate()
    if script is None:
        return None
    try:
        return brotli_size(script), script.stat().st_size, script.name
    except FileNotFoundError:
        return None


limit = budget()
measured = measure()
if measured is None:
    # A failure, not a skip, for the reason `from_web_weight.py` states at the same point: this
    # grader ran as a SKIP once in continuous integration because `brotli` was absent, and the
    # workflow went green having graded nothing. An environment asked to grade a budget and unable
    # to is broken, and saying so is the only way that gets fixed.
    present = sorted(p.name for p in DIST.iterdir()
                     if GUEST_PATTERN.fullmatch(p.name)) if DIST.is_dir() else []
    print(f'CONF G6 FAIL -- no measurement. Is the web-slice distribution built '
          f'(`:samples:web-slice:wasmJsBrowserDistribution`), does it contain exactly one '
          f'{GUEST}, and is `brotli` on PATH? Found {present or "none"}.')
    print('CONF RESULT client=web passed=0 failed=1 skipped=0')
    sys.exit(1)

total, raw, name = measured
detail = f'{name} {total:,} bytes brotli of {limit:,} ({raw:,} uncompressed)'
if total <= limit:
    print(f'CONF G6 PASS -- {detail}')
    print('CONF RESULT client=web passed=1 failed=0 skipped=0')
    sys.exit(0)
print(f'CONF G6 FAIL -- {detail}. The guest is re-fetched on every release rather than cached '
      f'behind an immutable hash like the host, so this is a cost paid per visitor per publish. '
      f'A raise needs the three builds `budgets.tsv` requires.')
print('CONF RESULT client=web passed=0 failed=1 skipped=0')
sys.exit(1)
