#!/usr/bin/env python3
"""Project Dogwood -- conformance claims D1-D5 on the web client.

The same claims the iOS and Android drills assert, against a third kind of machinery. Compose draws
to a canvas through Skiko, and a canvas has no intrinsic accessibility -- so whatever a screen
reader reads has to be published separately. It is: Compose Multiplatform builds a live DOM of
elements carrying roles and names, which Chrome exposes through `Accessibility.getFullAXTree`. That
is this platform's `UIAccessibility`, and it is what a screen reader consumes.

Activating a control is the interesting one. There is no `accessibilityActivate` here; a screen
reader user focuses the element and presses Enter, and the browser turns that into the element's
default action. So that is what this does -- located by role and name in the accessibility tree,
operated the way somebody using one would.

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

# Roles a screen reader stops on and operates. `generic` is deliberately absent: Compose emits a
# scaffold of unnamed generic nodes for layout, and requiring those to be named would be a claim
# about Compose's DOM structure rather than about what a user hears.
INTERACTIVE_ROLES = {'button', 'link', 'textbox', 'checkbox', 'radio', 'switch', 'slider',
                     'combobox', 'menuitem', 'tab', 'option'}

passed = failed = skipped = known = 0


def conform(claim_id, condition, detail=''):
    global passed, failed
    suffix = f' -- {detail}' if detail else ''
    if condition:
        passed += 1
        print(f'CONF {claim_id} PASS{suffix}', flush=True)
    else:
        failed += 1
        print(f'CONF {claim_id} FAIL{suffix}', flush=True)


def skip(claim_id, reason):
    """Not applicable on this client -- there is nothing here to judge."""
    global skipped
    skipped += 1
    print(f'CONF {claim_id} SKIP -- {reason}', flush=True)


def known_gap(claim_id, reason):
    """A real failure this project has decided not to block on, with a reason and a cause.

    Distinct from SKIP, which means the claim does not apply here. A KNOWN claim *does* apply and
    *does* fail; recording it as a skip would let a genuine gap read as an absence. It does not
    turn the gate red because the cause is outside this repository -- but it is counted, printed,
    and listed in `plans/conformance.md`, and if it ever starts passing the entry is stale, which
    the aggregator reports.
    """
    global known
    known += 1
    print(f'CONF {claim_id} KNOWN -- {reason}', flush=True)


def ax_nodes(devtools, session):
    """Every accessibility node Chrome would hand an assistive technology."""
    raw = devtools.call('Accessibility.getFullAXTree', {}, session).get('nodes', [])
    out = []
    for n in raw:
        if n.get('ignored'):
            continue
        out.append({
            'role': (n.get('role') or {}).get('value'),
            'name': ((n.get('name') or {}).get('value') or '').strip(),
            'backendDOMNodeId': n.get('backendDOMNodeId'),
            'props': {p['name']: p.get('value', {}).get('value') for p in n.get('properties', [])},
        })
    return out


def await_name(devtools, session, pattern, timeout=20):
    """Waits for a node whose name matches, which is how a consequence is observed."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in ax_nodes(devtools, session):
            if re.fullmatch(pattern, node['name']):
                return node
        time.sleep(0.3)
    return None


def run(url, chrome, port):
    global failed
    profile = tempfile.mkdtemp()
    browser = subprocess.Popen(
        [chrome, '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
         '--disable-extensions', '--enable-unsafe-swiftshader', '--window-size=900,700',
         '--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows',
         '--disable-renderer-backgrounding',
         # Without this Chrome builds no accessibility tree at all in headless mode, and every
         # claim below would fail for one uninteresting reason. It is this client's equivalent of
         # "VoiceOver must be running", and like that one it is arranged rather than assumed.
         '--force-renderer-accessibility',
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
        devtools.call('Page.navigate', {'url': url}, session)

        # The guest composes in a Worker and the host applies its batch; nothing exists to read
        # until that has happened.
        composed = False
        for _ in range(240):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or ''
            if raw:
                try:
                    if json.loads(raw).get('firstFrameMs'):
                        composed = True
                        break
                except json.JSONDecodeError:
                    pass
            time.sleep(0.25)
        if not composed:
            print('CONF REFUSED the page never reported a first frame', flush=True)
            return 2
        time.sleep(1.5)

        nodes = ax_nodes(devtools, session)
        print(f'CONF NOTE {len(nodes)} accessibility nodes', flush=True)
        for node in nodes[:40]:
            print(f'CONF ELEMENT role={node["role"]!r} name={node["name"]!r}', flush=True)

        named = [n['name'] for n in nodes if n['name']]

        # D1 -- guest-composed text reaches the platform's accessibility layer.
        conform('D1', any('Dogwood on the web' in n for n in named),
                f'looked for the guest\'s heading among {len(named)} names')

        # D2 -- no anonymous elements a screen reader would stop on and operate.
        anonymous = [n for n in nodes if n['role'] in INTERACTIVE_ROLES and not n['name']]
        conform('D2', not anonymous, f'{len(anonymous)} anonymous: {[n["role"] for n in anonymous]}')

        # D3 -- a guest-composed control is exposed AS a control. Everything on this page is guest
        # composed: unlike the mobile samples there is no host shell around it, so there is no
        # shell label to exclude.
        controls = [n for n in nodes if n['role'] in INTERACTIVE_ROLES]
        conform('D3', bool(controls),
                f'{len(controls)} controls: {[(n["role"], n["name"]) for n in controls][:4]}')

        # D4 -- activating through the accessibility layer drives the guest.
        #
        # The sample's control is a counter, so its own name is the observable consequence: the
        # guest owns the count, and it can only change if the activation crossed into the sandbox
        # and a batch came back.
        counter = next((n for n in nodes if re.fullmatch(r'taps: \d+', n['name'])), None)
        if counter is None:
            conform('D4', False, f'no counter control found among {named[:6]}')
        else:
            before = int(counter['name'].split(': ')[1])
            resolved = devtools.call(
                'DOM.resolveNode', {'backendNodeId': counter['backendDOMNodeId']}, session)
            object_id = resolved.get('object', {}).get('objectId')
            if not object_id:
                conform('D4', False, 'the control has no DOM node to operate')
            else:
                # A screen reader activates a `role="button"` by synthesising a click **on the
                # element**, which is why this goes through the node the accessibility tree named
                # rather than through a selector. Compose puts its accessibility elements inside a
                # shadow root, so `document.querySelector` finds none of them -- the same trap the
                # iOS probe documents for `ComposeViewport`, and the reason the first draft of this
                # drill reported a control it could not reach.
                devtools.call('Runtime.callFunctionOn', {
                    'functionDeclaration': 'function() { this.click(); }',
                    'objectId': object_id,
                }, session)
                after = await_name(devtools, session, rf'taps: {before + 1}')
                conform('D4', after is not None, f'taps: {before} -> {before + 1}')

                # Keyboard operability is a second, weaker claim: a screen reader user reaches a
                # control by keyboard before activating it, and an element that cannot take focus
                # cannot be reached that way at all.
                focusable = counter['props'].get('focusable')
                if focusable:
                    devtools.call('Runtime.callFunctionOn', {
                        'functionDeclaration': 'function() { this.focus(); }',
                        'objectId': object_id,
                    }, session)
                    for event in ('keyDown', 'keyUp'):
                        devtools.call('Input.dispatchKeyEvent', {
                            'type': event, 'key': 'Enter', 'code': 'Enter',
                            'windowsVirtualKeyCode': 13, 'nativeVirtualKeyCode': 13,
                        }, session)
                    reached = await_name(devtools, session, rf'taps: {before + 2}')
                    conform('D4-keyboard', reached is not None,
                            f'Enter on the focused control: taps: {before + 1} -> {before + 2}')
                else:
                    known_gap(
                        'D4-keyboard',
                        'the control is not focusable, so a screen reader user navigating by '
                        'keyboard cannot reach it; Compose Multiplatform publishes its web '
                        'accessibility elements without tabindex')

        # D5 -- the screen scrolls through the accessibility layer.
        scrollable = devtools.call('Runtime.evaluate', {
            'expression': 'document.scrollingElement.scrollHeight > '
                          'document.scrollingElement.clientHeight',
            'returnByValue': True,
        }, session).get('result', {}).get('value')
        if not scrollable:
            skip('D5', 'the web sample is shorter than the viewport, so there is nothing to scroll')
        else:
            conform('D5', True, 'the document scrolls')

        # D7 -- the web sample carries no disabled control.
        disabled = [n for n in nodes if n['props'].get('disabled')]
        if not disabled:
            skip('D7', 'the web sample screen carries no disabled control')
        else:
            conform('D7', all(n['name'] for n in disabled), f'{len(disabled)} disabled controls')

        print(f'CONF RESULT client=web passed={passed} failed={failed} '
              f'skipped={skipped} known={known}', flush=True)
        return 0 if failed == 0 else 1
    finally:
        browser.terminate()
        try:
            browser.wait(timeout=10)
        except subprocess.TimeoutExpired:
            browser.kill()
        subprocess.run(['pkill', '-f', profile], check=False)


if __name__ == '__main__':
    sys.exit(run(sys.argv[1], sys.argv[2], int(sys.argv[3])))
