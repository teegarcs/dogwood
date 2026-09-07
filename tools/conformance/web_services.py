#!/usr/bin/env python3
"""Project Dogwood -- conformance claims J1-J4 and A7 on the web client.

The host's services, and whether they actually reach a guest running in a Web Worker.

The mobile profile hands the guest a `DogwoodServiceHost` and Zipline carries the objects across.
A Worker boundary carries no object references, so the web profile splits the same surface: facts
the host knows at start cross once in a `start` message, and things the guest tells the host cross
as one-way messages. Until 2026-09-07 the web profile did neither, and the sample's own Diagnostics
screen said so in plain words -- `surface revision 0 (unreported)`, `host clock unavailable` -- for
as long as the web guest existed. It ran the same screens as the mobile payload and ran them blind.

**Read off the screen, not off the page's log.** The guest composes these values into text, so what
the accessibility tree carries is what the guest actually received. A host log line saying "sent"
is the host agreeing with itself.

`A7` is here rather than with the other protocol claims for a reason that is not filing: what
carries a guest's state across a code update on this platform **is** the start message, so the two
are one mechanism. A code update is the normal case on this architecture -- it is the whole selling
point -- and the web profile had never once been made to do one.

Emits the `CONF` grammar from `plans/conformance.md`.
"""
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, __file__.rsplit('/', 2)[0] + '/web-ttff')
from cdp import Devtools  # noqa: E402
from web_accessibility import ax_nodes, scroll_through  # noqa: E402

passed = failed = 0
lines = []


def conform(claim_id, condition, detail=''):
    global passed, failed
    suffix = f' -- {detail}' if detail else ''
    if condition:
        passed += 1
        lines.append(f'CONF {claim_id} PASS{suffix}')
    else:
        failed += 1
        lines.append(f'CONF {claim_id} FAIL{suffix}')
    print(lines[-1], flush=True)


def run(url, chrome, port):
    profile = tempfile.mkdtemp()
    browser = subprocess.Popen(
        [chrome, '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
         '--disable-extensions', '--enable-unsafe-swiftshader', '--window-size=900,700',
         '--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows',
         '--disable-renderer-backgrounding', '--force-renderer-accessibility',
         f'--remote-debugging-port={port}', f'--user-data-dir={profile}', 'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        version = None
        for _ in range(100):
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/json/version', timeout=1) as r:
                    version = json.load(r)
                break
            except Exception:
                time.sleep(0.2)
        if version is None:
            print('CONF REFUSED Chrome never opened its debugging port', flush=True)
            return 2

        devtools = Devtools(version['webSocketDebuggerUrl'])
        target = devtools.call('Target.createTarget', {'url': 'about:blank'})['targetId']
        session = devtools.call(
            'Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
        devtools.call('Runtime.enable', {}, session)
        devtools.call('DOM.enable', {}, session)
        devtools.call('Accessibility.enable', {}, session)

        # ---------------------------------------------------------------------------------
        # J1 and J3 -- the Diagnostics screen, which composes what the host told it.
        # ---------------------------------------------------------------------------------
        devtools.call('Page.navigate', {
            'url': f'{url}?manifest=dogwood-manifest-kotlin.json&entry=about'}, session)
        report = {}
        for _ in range(240):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or ''
            if raw:
                try:
                    report = json.loads(raw)
                except json.JSONDecodeError:
                    report = {}
                if report.get('done'):
                    break
            time.sleep(0.25)
        if not report.get('done'):
            print('CONF REFUSED the page never finished loading the guest', flush=True)
            return 2
        time.sleep(1.5)

        names = [n['name'] for n in ax_nodes(devtools, session) if n['name']]
        offered = next((n for n in names if 'log' in n and ',' in n), '')

        # J1 -- the services a host wired reach the guest, and the guest can read them. The clock is
        # the one with an observable value: a millisecond count the guest could not have invented.
        clock = next((n for n in names if re.fullmatch(r'host clock \d{13}', n)), None)
        zone = next((n for n in names if n.startswith('time zone ') and '/' in n), None)
        conform('J1', clock is not None and zone is not None,
                f'offered [{offered}]; {clock!r}, {zone!r}')

        # J3 -- the dictionary versions this client implements reach the guest, which is what a
        # guest branches on to decide what it may use. `unreported` is what an empty map renders as.
        revision = next((n for n in names if n.startswith('surface revision ')), '')
        conform('J3', bool(revision) and 'unreported' not in revision, revision or 'no revision line')

        # ---------------------------------------------------------------------------------
        # J2 -- launch parameters reach the experience the host named.
        #
        # A different entry point, chosen by the *host* through the page's own URL rather than by
        # the guest reading its Worker's. The city is a launch parameter and the rows come from a
        # fetch the guest made through its network service, so a screen carrying both is both
        # halves at once.
        # ---------------------------------------------------------------------------------
        devtools.call('Page.navigate', {
            'url': f'{url}?manifest=dogwood-manifest-kotlin.json&entry=explore'}, session)
        for _ in range(240):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or ''
            if raw and json.loads(raw).get('done'):
                break
            time.sleep(0.25)
        time.sleep(1.5)
        named = [name for (_, name) in scroll_through(devtools, session, steps=6)]
        tokyo = [n for n in named if 'Tokyo' in n]
        conform('J2', bool(tokyo),
                f'the host named the entry point and passed a city: {tokyo[:3] or named[:6]}')

        # J4 -- a route the host does not handle is declined, and recorded rather than dropped.
        #
        # Graded here rather than on the Diagnostics screen, and the difference is the point: that
        # screen has a button which *would* ask for a route and nothing presses it, so asserting
        # there would be asserting that nothing happened. This screen's own row asks to navigate as
        # soon as the page's harness taps it, which makes the refusal an outcome rather than an
        # absence.
        raw = devtools.call('Runtime.evaluate', {
            'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
        }, session).get('result', {}).get('value') or '{}'
        log = json.loads(raw).get('log', [])
        conform('J4', any('no host route for' in line for line in log),
                f'the host refused and recorded it: {[l for l in log if "route" in l]}')

        # ---------------------------------------------------------------------------------
        # A7 -- a code update preserves what the user was doing.
        #
        # `entry=app` is the one experience with visible saveable state: which tab is open, held in
        # `rememberSaveable` above the navigation. A code update replaces the Worker entirely -- a
        # new script, a new module, a new composition -- so the only route from the old guest's
        # values to the new one's is through the host, and the assertion is that the screen comes
        # back where the user left it rather than at the default tab.
        # ---------------------------------------------------------------------------------
        devtools.call('Page.navigate', {
            'url': f'{url}?manifest=dogwood-manifest-kotlin.json&entry=app'}, session)
        for _ in range(240):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or ''
            if raw and json.loads(raw).get('done'):
                break
            time.sleep(0.25)
        time.sleep(1.5)

        # Move off the default tab, through the accessibility layer, the way a user would.
        tab = next((n for n in ax_nodes(devtools, session)
                    if n['role'] == 'button' and n['name'] == 'Diagnostics'), None)
        moved = False
        if tab is not None:
            handle = devtools.call(
                'DOM.resolveNode', {'backendNodeId': tab['backendDOMNodeId']}, session,
            ).get('object', {}).get('objectId')
            if handle:
                devtools.call('Runtime.callFunctionOn', {
                    'functionDeclaration': 'function() { this.click(); }', 'objectId': handle,
                }, session)
                for _ in range(40):
                    if any(n['name'] == 'Diagnostics' and n['role'] != 'button'
                           for n in ax_nodes(devtools, session)):
                        moved = True
                        break
                    time.sleep(0.25)
        # The control. Without it, "still on Diagnostics after the update" is satisfied by a screen
        # that never left the default tab, and the claim would hold vacuously.
        conform('A7-control', moved, 'the tab moved off its default before the update')

        devtools.call('Runtime.evaluate', {
            'expression': 'globalThis.__dogwoodCodeUpdate = true', 'returnByValue': True,
        }, session)
        updated = False
        for _ in range(120):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or '{}'
            if json.loads(raw).get('codeUpdates') == '1':
                updated = True
                break
            time.sleep(0.25)
        time.sleep(2)

        names_after = [n['name'] for n in ax_nodes(devtools, session) if n['name']]
        back = any('Everything on this screen came from the host' in n for n in names_after)
        conform('A7', updated and back,
                f'the guest was replaced ({updated}) and came back on the tab the user left it on '
                f'({back}): {names_after[:6]}')

        return 1 if failed else 0
    finally:
        browser.terminate()
        try:
            browser.wait(timeout=10)
        except subprocess.TimeoutExpired:
            browser.kill()


status = run(sys.argv[1], sys.argv[2], int(sys.argv[3]))
print(f'CONF RESULT client=web passed={passed} failed={failed} skipped=0', flush=True)
sys.exit(status)
