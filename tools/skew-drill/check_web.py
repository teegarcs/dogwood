#!/usr/bin/env python3
"""Project Dogwood -- skew containment on the web, read off a real page in a real browser.

The Android and iOS drills' twin. The same three claims -- A2, A3 and A4 -- against a third kind of
machinery, and produced the same way: a client built at dictionary version N meeting a payload
built at N+1, because the host bindings are compiled into the page's WebAssembly module and the
guest is a separate script the page fetches at run time.

**And one claim the mobile clients cannot make.** This client checks the payload's declared
dictionary versions *before* it creates the Worker (ADR-032's sidecar), so a skewed payload that
declares itself is refused before a line of guest code runs -- claim B3. The mobile hosts have no
such pre-flight check: their Zipline manifests carry no segment versions, so the render-time rules
below are the only line of containment they have. That asymmetry is real, and this drill runs both
halves rather than picking the flattering one:

  * with the sidecar left as it is, the skew is **undeclared** and must be contained at render time
  * with the sidecar telling the truth about N+1, the payload must be **refused** outright

Emits the `CONF` grammar so `tools/conformance/aggregate.py` reads this run like any other.
"""
import json
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, __file__.rsplit('/', 2)[0] + '/web-ttff')
from cdp import Devtools  # noqa: E402

URL, CHROME, PORT = sys.argv[1], sys.argv[2], int(sys.argv[3])
OUT = sys.argv[4]

lines = []
passed = failed = 0


def conform(claim, ok, detail):
    global passed, failed
    if ok:
        passed += 1
        lines.append(f'CONF {claim} PASS -- {detail}')
    else:
        failed += 1
        lines.append(f'CONF {claim} FAIL -- {detail}')


def report(devtools, session):
    """The page's own account of itself, or None until it has published one."""
    raw = devtools.call('Runtime.evaluate', {
        'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
    }, session).get('result', {}).get('value') or ''
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def await_report(devtools, session, key, seconds=90):
    deadline = time.time() + seconds
    while time.time() < deadline:
        current = report(devtools, session)
        if current and current.get(key):
            return current
        time.sleep(0.25)
    return report(devtools, session)


def skew_markers(devtools, session):
    """Every `SKEW-` marker the accessibility tree carries, in tree order, with its position.

    The accessibility tree rather than the host's own tree, for the reason every drill in this
    repository gives: the claims are about a *screen*. Compose draws to a canvas, so what a screen
    reader reads is a live DOM Compose publishes alongside it -- which is also the only DOM with
    positions in it, and A2 is a geometric claim.
    """
    raw = devtools.call('Accessibility.getFullAXTree', {}, session).get('nodes', [])
    found = []
    for node in raw:
        if node.get('ignored'):
            continue
        name = ((node.get('name') or {}).get('value') or '').strip()
        if not name.startswith('SKEW-'):
            continue
        if any(name == m['name'] for m in found):
            continue
        top = None
        backend = node.get('backendDOMNodeId')
        if backend is not None:
            try:
                box = devtools.call('DOM.getBoxModel', {'backendNodeId': backend}, session)
                # `content` is eight numbers: four corners, clockwise from the top left.
                top = box['model']['content'][1]
            except Exception:
                top = None
        found.append({'name': name, 'top': top})
    return found


def run():
    profile = tempfile.mkdtemp()
    browser = subprocess.Popen(
        [CHROME, '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
         '--disable-extensions', '--enable-unsafe-swiftshader', '--window-size=900,900',
         '--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows',
         '--disable-renderer-backgrounding',
         # Without this Chrome builds no accessibility tree at all in headless mode, and every
         # claim below would fail for one uninteresting reason. It is this client's equivalent of
         # "VoiceOver must be running" on iOS, and like that one it is arranged rather than assumed.
         '--force-renderer-accessibility',
         f'--remote-debugging-port={PORT}', f'--user-data-dir={profile}', 'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        version = None
        for _ in range(100):
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{PORT}/json/version', timeout=1) as r:
                    version = json.load(r)
                break
            except Exception:
                time.sleep(0.2)
        if version is None:
            print('SKEW REFUSED Chrome never opened its debugging port', file=sys.stderr)
            return 2

        devtools = Devtools(version['webSocketDebuggerUrl'])
        target = devtools.call('Target.createTarget', {'url': 'about:blank'})['targetId']
        session = devtools.call(
            'Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
        devtools.call('Runtime.enable', {}, session)
        devtools.call('DOM.enable', {}, session)
        devtools.call('Accessibility.enable', {}, session)

        # -------------------------------------------------------------------------------------
        # Half one: undeclared skew, contained at render time.
        # -------------------------------------------------------------------------------------
        devtools.call('Page.navigate', {'url': f'{URL}?manifest=dogwood-manifest-kotlin.json'},
                      session)
        page = await_report(devtools, session, 'done')
        if not page:
            print('SKEW REFUSED the page never published a report', file=sys.stderr)
            return 2
        if page.get('done') != 'true':
            print(f'SKEW REFUSED the page never finished: {page.get("log", [])[-3:]}',
                  file=sys.stderr)
            return 2
        # The accessibility tree lags the composition it describes; Compose republishes it on its
        # own schedule rather than synchronously with a batch.
        time.sleep(2)

        markers = skew_markers(devtools, session)
        names = [m['name'] for m in markers]
        tops = {m['name']: m['top'] for m in markers}
        for marker in markers:
            lines.append(f'CONF ELEMENT {marker["name"]} top={marker["top"]}')

        # The control. Without it a page that failed to load reads as three passes, because every
        # claim below is satisfied by absence.
        conform('A2-control', 'SKEW-BEFORE' in names and 'SKEW-AFTER' in names,
                f'the skewed screen rendered: {names}')

        # A2 -- an unknown widget tag becomes a placeholder and the sibling after it keeps its
        # place. The placeholder draws nothing, so the evidence is the position of what follows it:
        # had the create been skipped rather than placeheld, every later index in that slot would
        # have shifted by one.
        before, after = tops.get('SKEW-BEFORE'), tops.get('SKEW-AFTER')
        if before is not None and after is not None:
            conform('A2', after > before,
                    f'SKEW-BEFORE at y={before}, SKEW-AFTER at y={after}'
                    + (f' -- the placeholder occupies its slot (gap {after - before})'
                       if after > before else ''))
        else:
            # Stated rather than silently downgraded: order in the accessibility tree follows
            # layout order, so it is real evidence, and it is weaker evidence than geometry.
            ordered = (names.index('SKEW-BEFORE') < names.index('SKEW-AFTER')
                       if 'SKEW-BEFORE' in names and 'SKEW-AFTER' in names else False)
            conform('A2', ordered,
                    'no box model for these nodes; asserted on accessibility-tree order instead, '
                    f'which is weaker: {names}')

        # A3 -- an unknown property on a widget owning no affordance is ignored, and it renders.
        conform('A3', 'SKEW-BADGE' in names,
                'the badge carrying an unknown property rendered' if 'SKEW-BADGE' in names
                else f'the badge is missing; visible markers: {names}')

        # A4 -- an unknown property on a widget that *owns* an affordance withholds it entirely.
        # One of the things the payload might have been saying is "this is disabled", and this
        # client cannot read it. See ADR-031.
        conform('A4', 'SKEW-PAY' not in names,
                'the button is absent from the accessibility tree' if 'SKEW-PAY' not in names
                else 'the button rendered while carrying an unreadable affordance-bearing property')

        # And it is reported. Containment nobody can see teaches no team that its payloads have
        # moved ahead of its devices, which is the whole purpose of `SkewReport`.
        #
        # Re-read rather than taken from the report captured above, because the page *polls* this:
        # a withheld widget is recorded by the binding that declined to draw it, during a
        # composition that had not happened when the earlier fields were published. The first run
        # of this drill read the stale copy and reported a client that had contained the skew
        # correctly as having failed to report it.
        skew = ''
        deadline = time.time() + 15
        while time.time() < deadline:
            skew = (report(devtools, session) or {}).get('skew', '')
            if 'withheld=' in skew:
                break
            time.sleep(0.5)
        conform('A4-reported', 'withheld=' in skew, skew or 'the page published no skew report')

        # -------------------------------------------------------------------------------------
        # Half two: declared skew, refused before the Worker exists.
        # -------------------------------------------------------------------------------------
        devtools.call('Page.navigate', {'url': f'{URL}?manifest=dogwood-manifest-skewed.json'},
                      session)
        refused = await_report(devtools, session, 'workerCreated', seconds=60)
        created = (refused or {}).get('workerCreated')
        conform('B3', created == 'false',
                f'workerCreated={created}, refused={(refused or {}).get("refused")} '
                f'-- against a payload that is genuinely newer, not a fabricated manifest')
        return 1 if failed else 0
    finally:
        browser.terminate()


status = run()
lines.append(f'CONF RESULT client=web passed={passed} failed={failed} skipped=0')
open(OUT, 'w').write('\n'.join(lines) + '\n')
print('\n'.join(lines))
sys.exit(status)
