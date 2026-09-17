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

**It runs against the real Kotlin guest**, not the hand-written JavaScript one. Until 2026-09-07 it
did not, and `plans/conformance.md` Part 7 named that as the cheapest remaining upgrade to what the
web column means: every claim here was evidence about a thirty-line script no product would write.
The page is loaded with `?manifest=dogwood-manifest-kotlin.json`, so what the accessibility layer
is asked about is the **same Diagnostics screen** the Android and iOS drills assert on, composed
from `samples/slice-screens` in a Web Worker.

That change also turned `D7` from a skip into a graded claim: the hand-written guest had no disabled
control and the shared screen has one, deliberately, for exactly this reason.

Emits the `CONF` grammar from `plans/conformance.md`.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

# How long to wait for a page to do something, as a multiple of what a development machine needs.
# `tier-c.yml` sets `DOGWOOD_DRILL_PATIENCE` for the hosted runners, where a Compose canvas renders
# through a software rasteriser on two shared cores; unset, nothing here changes. See docs/checks.md.
PATIENCE = float(os.environ.get('DOGWOOD_DRILL_PATIENCE', '1'))


def patiently(seconds):
    """A deadline in seconds, stretched by `DOGWOOD_DRILL_PATIENCE`."""
    return seconds * PATIENCE


sys.path.insert(0, __file__.rsplit('/', 2)[0] + '/web-ttff')
from cdp import Devtools  # noqa: E402

# Roles a screen reader stops on and operates. `generic` is deliberately absent: Compose emits a
# scaffold of unnamed generic nodes for layout, and requiring those to be named would be a claim
# about Compose's DOM structure rather than about what a user hears.
INTERACTIVE_ROLES = {'button', 'link', 'textbox', 'checkbox', 'radio', 'switch', 'slider',
                     'combobox', 'menuitem', 'tab', 'option'}

passed = failed = skipped = 0


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


def is_composes_backing_input(devtools, session, node):
    """Is this Compose Multiplatform's own hidden text-entry element rather than a guest control?

    Compose draws to a canvas and cannot receive keystrokes, so it keeps one transparent `<input>`
    positioned over the focused field to collect them. Chrome publishes it as an unnamed `textbox`,
    and a screen reader would stop on it -- which is a real observation about Compose's web
    accessibility and **not** a control this payload composed.

    Identified by the element rather than by guessing from the tree's shape: its inline style is
    written with `--compose-internal-web-backing-input-*` custom properties, and it is
    `color: transparent; caret-color: transparent; z-index: -1`. Excluding it by position, or by
    "the first textbox", would exclude a real anonymous field the day a screen had one.
    """
    backend = node.get('backendDOMNodeId')
    if backend is None:
        return False
    handle = devtools.call('DOM.resolveNode', {'backendNodeId': backend}, session)
    object_id = handle.get('object', {}).get('objectId')
    if not object_id:
        return False
    style = devtools.call('Runtime.callFunctionOn', {
        'functionDeclaration': 'function() { return this.getAttribute("style") || ""; }',
        'objectId': object_id, 'returnByValue': True,
    }, session).get('result', {}).get('value') or ''
    return '--compose-internal-web-backing-input' in style


def scroll_through(devtools, session, steps=30, delta=250):
    """Every accessibility node the screen publishes, gathered the way a user reaches them.

    One viewport is not the screen. The shared Diagnostics screen is several times taller than the
    window, and Compose publishes accessibility elements only for what it has laid out -- so a drill
    that reads the tree once is asserting about the top of a page. That is how the first run of this
    version reported "no Expand/Collapse control": the control exists, four screens down.

    Returns the nodes keyed by (role, name), keeping the last node seen for each, because a node's
    DOM handle is only useful while it is still on screen.
    """
    found = {}
    for _ in range(steps):
        for node in ax_nodes(devtools, session):
            if node['name']:
                found[(node['role'], node['name'])] = node
        devtools.call('Input.dispatchMouseEvent', {
            'type': 'mouseWheel', 'x': 450, 'y': 400, 'deltaX': 0, 'deltaY': delta,
            'button': 'none', 'clickCount': 0,
        }, session)
        time.sleep(0.35)
    for node in ax_nodes(devtools, session):
        if node['name']:
            found[(node['role'], node['name'])] = node
    return found


def wheel(devtools, session, delta):
    devtools.call('Input.dispatchMouseEvent', {
        'type': 'mouseWheel', 'x': 450, 'y': 400, 'deltaX': 0, 'deltaY': delta,
        'button': 'none', 'clickCount': 0,
    }, session)


def reach(devtools, session, names, steps=40, delta=250):
    """Scrolls back to the top and down again, returning a **live** node with one of [names].

    Live is the whole point. `scroll_through` returns what the screen published, which is enough to
    assert that a control exists and useless for operating one: by the time that walk ends the
    control is several screens above, and Compose has taken its element out of the tree. Clicking
    the handle it returned does nothing at all, silently -- which is exactly how the first version
    of this check reported `Expand -> Collapse` as a failure while the toggle worked perfectly.
    """
    for _ in range(steps + 10):
        wheel(devtools, session, -delta)
    time.sleep(0.5)
    for _ in range(steps):
        for node in ax_nodes(devtools, session):
            if node['name'] in names:
                return node
        wheel(devtools, session, delta)
        time.sleep(0.35)
    return None


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
        # The real guest. See the header: the sidecar names the Worker script, and
        # `dogwood-manifest-kotlin.json` is the one that names the compiled Kotlin composition.
        devtools.call(
            'Page.navigate',
            {'url': f'{url}?manifest=dogwood-manifest-kotlin.json'},
            session,
        )

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
        # **Waited for, not slept through.** This was `time.sleep(1.5)` and then one read of the
        # tree, and on a hosted runner `D1` failed with `looked for the guest's heading among 1
        # names: ['Dogwood web slice']` -- only the page title, because the accessibility DOM had
        # not been published yet. `D2` through `D5` then passed on the very next reads, so the
        # guest was there; the drill had simply looked once, early.
        #
        # A first frame is not an accessibility tree. Compose publishes the tree separately and
        # afterwards, so the thing to wait for is the tree having something in it other than the
        # page's own title. A fixed sleep is a guess about a machine; this is the consequence
        # itself, and it also returns sooner than 1.5 seconds on a machine that is quick.
        nodes = []
        tree_deadline = time.time() + patiently(20)
        while time.time() < tree_deadline:
            nodes = ax_nodes(devtools, session)
            if len([n for n in nodes if n['name'] and n['role'] != 'RootWebArea']) > 1:
                break
            time.sleep(0.25)

        print(f'CONF NOTE {len(nodes)} accessibility nodes', flush=True)
        for node in nodes[:40]:
            print(f'CONF ELEMENT role={node["role"]!r} name={node["name"]!r}', flush=True)

        named = [n['name'] for n in nodes if n['name']]

        # D1 -- guest-composed text reaches the platform's accessibility layer. "Diagnostics" is
        # the first `SectionHeader` on the shared screen, composed in the sandbox and crossed as a
        # property; nothing on the host side knows the word.
        conform('D1', any('Diagnostics' in n for n in named),
                f'looked for the guest\'s heading among {len(named)} names: {named[:8]}')

        # D2 -- no anonymous elements a screen reader would stop on and operate.
        #
        # Compose's own transparent text-entry element is excluded and the exclusion is *counted*,
        # not silently dropped: it is unnamed, a screen reader does stop on it, and it is not a
        # control this payload composed. See `is_composes_backing_input`.
        anonymous = [n for n in nodes if n['role'] in INTERACTIVE_ROLES and not n['name']]
        composes_own = [n for n in anonymous if is_composes_backing_input(devtools, session, n)]
        guests = [n for n in anonymous if n not in composes_own]
        conform('D2', not guests,
                f'{len(guests)} anonymous: {[n["role"] for n in guests]}'
                + (f' ({len(composes_own)} excluded as Compose\'s own backing input)'
                   if composes_own else ''))

        # The whole screen, reached the way a user reaches it. Everything below needs controls that
        # are several screens down; see `scroll_through`.
        whole_screen = scroll_through(devtools, session)
        print(f'CONF NOTE {len(whole_screen)} named nodes across the whole screen', flush=True)

        # D3 -- a guest-composed control is exposed AS a control. Everything on this page is guest
        # composed: unlike the mobile samples there is no host shell around it, so there is no
        # shell label to exclude.
        controls = [n for (role, _), n in whole_screen.items() if role in INTERACTIVE_ROLES]
        conform('D3', bool(controls),
                f'{len(controls)} controls: {[(n["role"], n["name"]) for n in controls][:4]}')

        # D5 -- the screen scrolls, and the evidence is that scrolling *reached* something.
        #
        # It used to ask whether `document.scrollingElement` was taller than the viewport, which on
        # this page is a question about the host's own hidden report element rather than about the
        # guest's list. The guest's content lives inside a Compose-drawn canvas that is exactly the
        # size of the window; what moves is a lazy list inside it, and the only way to see that from
        # outside is that names appear which were not published before.
        conform('D5', len(whole_screen) > len(nodes),
                f'scrolling published {len(whole_screen)} named nodes where one viewport had '
                f'{len([n for n in nodes if n["name"]])}')

        # D4 -- activating through the accessibility layer drives the guest.
        #
        # The sample's control is a counter, so its own name is the observable consequence: the
        # guest owns the count, and it can only change if the activation crossed into the sandbox
        # and a batch came back.
        #
        # On the shared screen that control is the Expand/Collapse toggle, and **which of the two
        # words it currently carries is not assumed**: the page's own harness taps the first
        # enabled `PrimaryButton` on load to exercise the event path, and that is this one. So the
        # drill reads the label it finds and asserts it becomes the other -- which is the same
        # consequence either way round.
        toggle = reach(devtools, session, ('Expand', 'Collapse'))
        if toggle is None:
            conform('D4', False, f'no Expand/Collapse control found among {named[:8]}')
        else:
            before = toggle['name']
            expected = 'Collapse' if before == 'Expand' else 'Expand'
            resolved = devtools.call(
                'DOM.resolveNode', {'backendNodeId': toggle['backendDOMNodeId']}, session)
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
                after = await_name(devtools, session, expected)
                conform('D4', after is not None, f'{before} -> {expected}')

                # D4-keyboard -- a screen reader user reaches the control by keyboard and
                # operates it there.
                #
                # **Test the behaviour, not the property.** The first version of this check read
                # the `focusable` flag off the accessibility node, found it absent, and concluded
                # the control could not be reached by keyboard. That was wrong, and wrong in the
                # way this project keeps catching: it asserted a property instead of running the
                # thing. Compose does not put `tabindex` on each element -- it gives the canvas
                # container one focusable node and routes Tab and Enter through its own focus
                # system. So the elements look unreachable and are not. Pressing the keys settles
                # it in a way reading the flag never could.
                container = next(
                    (n for n in nodes if n['props'].get('focusable') and n['role'] != 'RootWebArea'),
                    None)
                if container is None:
                    conform('D4-keyboard', False, 'nothing on the page can take keyboard focus')
                else:
                    handle = devtools.call(
                        'DOM.resolveNode',
                        {'backendNodeId': container['backendDOMNodeId']}, session,
                    ).get('object', {}).get('objectId')
                    if handle:
                        devtools.call('Runtime.callFunctionOn', {
                            'functionDeclaration': 'function() { this.focus(); }',
                            'objectId': handle,
                        }, session)
                    reached = None
                    # Tab walks Compose's focus order; how many stops precede the control is a
                    # property of the screen, not of the platform, so it is searched rather than
                    # assumed.
                    for _ in range(8):
                        for event in ('keyDown', 'keyUp'):
                            devtools.call('Input.dispatchKeyEvent', {
                                'type': event, 'key': 'Tab', 'code': 'Tab',
                                'windowsVirtualKeyCode': 9, 'nativeVirtualKeyCode': 9,
                            }, session)
                        time.sleep(0.3)
                        for event in ('rawKeyDown', 'char', 'keyUp'):
                            payload = {'type': event, 'key': 'Enter', 'code': 'Enter',
                                       'windowsVirtualKeyCode': 13, 'nativeVirtualKeyCode': 13}
                            if event == 'char':
                                payload['text'] = '\r'
                            devtools.call('Input.dispatchKeyEvent', payload, session)
                        reached = await_name(devtools, session, before, timeout=2)
                        if reached:
                            break
                    conform('D4-keyboard', reached is not None,
                            f'Tab to the control, then Enter: {expected} -> {before}')

        # D7 -- a disabled control is announced as disabled. **It is not, on this client.**
        #
        # The shared screen carries two deliberately disabled buttons, `Unavailable` and
        # `Acme unavailable`, put there so this claim could be graded at all. Both reach the
        # accessibility tree as `<div role="button">` with a correct name and **no properties
        # whatsoever** -- no `disabled`, no `aria-disabled` -- so nothing distinguishes them from
        # the enabled button beside them. A screen reader user is not told, tries to operate it,
        # and is met with nothing.
        #
        # That is Compose Multiplatform's web accessibility layer rather than Dogwood's: the
        # composition marks the control disabled and the platform publishes role and name only.
        # Recorded as an exemption with a reason in `exempt.tsv` and drafted as upstream report 3,
        # rather than as a red cell that would sit there forever or a skip that would read as
        # "nothing to judge here".
        # Only the control, not the text inside it: Compose publishes a `button`, a `StaticText`
        # and an `InlineTextBox` for one control, so counting names would report three of each.
        named_disabled = [n for (role, name), n in whole_screen.items()
                          if role == 'button' and name in ('Unavailable', 'Acme unavailable')]
        announced = [n for n in named_disabled if n['props'].get('disabled')]
        if named_disabled and not announced:
            skip('D7', f'{len(named_disabled)} disabled controls are on screen and none of them is '
                       f'announced as disabled: Compose publishes role and name only, with no '
                       f'properties at all. See tools/upstream-reports/README.md #3')
        elif not named_disabled:
            conform('D7', False, 'the drill never reached the screen\'s disabled controls')
        else:
            conform('D7', all(n['name'] for n in announced), f'{len(announced)} disabled controls')

        print(f'CONF RESULT client=web passed={passed} failed={failed} skipped={skipped}',
              flush=True)
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
