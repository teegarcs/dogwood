#!/usr/bin/env python3
"""Project Dogwood -- the generated Material 3 tier, operated on the web client.

Claims `M1`-`M7` and `B6`, graded through Chrome's accessibility tree over the DevTools protocol,
which is the same instrument `web_accessibility.py` uses and for the same reason: it is what a
screen reader would be handed.

What is being asked here is not "does Material 3 work" -- Google owns that -- but whether the
*generated* binding between a payload and the library survives the whole path: a guest composes
`Button`, the stub sends a widget tag, the host's generated binding calls the real function, the
library renders, a person activates it through the accessibility layer, the event crosses back, and
the payload's own state changes. Every claim below ends at that last step, because it is the only
one that cannot be faked by a screen that merely looks right.

The screen is `MaterialScreen.kt` in `samples/slice-screens`, which all four clients render. Each
control on it has a **witness** beside it: a line of primitive-tier text whose content is a function
of that control's state (`m3.switch=on`). The witness is what makes one assertion work on four
clients with four different instruments.

    python3 tools/conformance/web_material.py <url> <chrome> <port>

Run by `tools/conformance/run-web.sh` alongside the other two web modules.
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import time
import urllib.request

sys.path.insert(0, __file__.rsplit('/', 2)[0] + '/web-ttff')
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from cdp import Devtools  # noqa: E402
from web_accessibility import ax_nodes  # noqa: E402

passed = failed = skipped = 0

# The sections of the catalogue, and one label from each that only exists if the tier rendered.
# Every one of these is composed *inside* a Material 3 component's slot, so a client that had lost
# the tier would keep the witnesses and lose these -- which is what makes M1 worth grading.
SECTIONS = [
    ('Buttons', 'Filled'),
    ('Selection', 'Send me the summary'),
    ('Chips', 'Add to trip'),
    ('Cards', 'A plain card'),
    ('Progress', 'Heavy and large'),
    ('App bars', 'Small bar'),
    ('Navigation', 'Flights'),
    ('Tabs', 'Outbound'),
    ('Dialogs', 'Open alert'),
    ('Sheets', 'Open the sheet'),
]


def conform(claim_id, condition, detail=''):
    global passed, failed
    suffix = f' -- {detail}' if detail else ''
    print(f'CONF {claim_id} {"PASS" if condition else "FAIL"}{suffix}', flush=True)
    if condition:
        passed += 1
    else:
        failed += 1


def names(devtools, session):
    return [n['name'] for n in ax_nodes(devtools, session) if n['name']]


def witness(devtools, session, prefix):
    """The current value of one witness line, or None if it is not on screen."""
    for name in names(devtools, session):
        if name.startswith(prefix):
            return name
    return None


def await_witness(devtools, session, prefix, was, timeout=15):
    """Waits for a witness to say something other than [was]. The consequence, not the click."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        now = witness(devtools, session, prefix)
        if now is not None and now != was:
            return now
        time.sleep(0.25)
    return None


def find(devtools, session, name, role=None):
    for node in ax_nodes(devtools, session):
        if node['name'] == name and (role is None or node['role'] == role):
            return node
    return None


def activate(devtools, session, node):
    """Activates a control the way an assistive technology does: a click on the element itself.

    Compose publishes its accessibility elements inside a shadow root, so a selector finds none of
    them; the node the tree named is the only handle there is. `web_accessibility.py` records what
    that cost to learn.
    """
    if node is None or node.get('backendDOMNodeId') is None:
        return False
    resolved = devtools.call('DOM.resolveNode', {'backendNodeId': node['backendDOMNodeId']}, session)
    object_id = resolved.get('object', {}).get('objectId')
    if not object_id:
        return False
    devtools.call('Runtime.callFunctionOn', {
        'functionDeclaration': 'function() { this.click(); }',
        'objectId': object_id,
    }, session)
    return True


def open_section(devtools, session, label):
    """Clicks a section chip and waits for the catalogue's own witness to agree."""
    chip = find(devtools, session, label)
    if not activate(devtools, session, chip):
        return False
    deadline = time.time() + 15
    while time.time() < deadline:
        if witness(devtools, session, 'm3.section=') is not None:
            # The witness names the section identifier, which is the label lowercased and
            # de-spaced; comparing to the chip's label would be comparing to the thing clicked.
            wanted = label.lower().replace(' ', '')
            if (witness(devtools, session, 'm3.section=') or '').endswith(wanted):
                return True
        time.sleep(0.25)
    return False


def start_browser(chrome, port):
    profile = tempfile.mkdtemp()
    browser = subprocess.Popen(
        [chrome, '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
         '--disable-extensions', '--enable-unsafe-swiftshader', '--window-size=900,900',
         '--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows',
         '--disable-renderer-backgrounding', '--force-renderer-accessibility',
         f'--remote-debugging-port={port}', f'--user-data-dir={profile}', 'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(100):
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/json/version', timeout=1) as r:
                return browser, profile, json.load(r)
        except Exception:
            time.sleep(0.2)
    return browser, profile, None


def open_page(devtools, url, query):
    target = devtools.call('Target.createTarget', {'url': 'about:blank'})['targetId']
    session = devtools.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
    for domain in ('Runtime', 'DOM', 'Accessibility'):
        devtools.call(f'{domain}.enable', {}, session)
    devtools.call('Page.navigate', {'url': f'{url}?{query}'}, session)
    return session


def await_first_frame(devtools, session, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        raw = devtools.call('Runtime.evaluate', {
            'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
        }, session).get('result', {}).get('value') or ''
        if raw:
            try:
                if json.loads(raw).get('firstFrameMs'):
                    return True
            except json.JSONDecodeError:
                pass
        time.sleep(0.25)
    return False


def read_report(devtools, session):
    raw = devtools.call('Runtime.evaluate', {
        'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
    }, session).get('result', {}).get('value') or ''
    try:
        return json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return {}


def run(url, chrome, port):
    global failed, skipped
    browser, profile, version = start_browser(chrome, port)
    try:
        if version is None:
            print('CONF REFUSED Chrome never opened its debugging port', flush=True)
            return 2
        devtools = Devtools(version['webSocketDebuggerUrl'])

        # ---------------------------------------------------------------------------------
        # B6 -- a payload that declares a generated tier the host lacks is refused before a
        # Worker exists. The control comes first, because every assertion about a refusal is
        # satisfied by a page that was broken for some other reason.
        # ---------------------------------------------------------------------------------
        tierless = open_page(devtools, url, 'manifest=dogwood-manifest-kotlin.json&tier=none')
        refused = None
        deadline = time.time() + 45
        while time.time() < deadline:
            current = read_report(devtools, tierless)
            if current.get('refused'):
                refused = current
                break
            time.sleep(0.25)
        report = refused or read_report(devtools, tierless)
        # `workerCreated` is a string field in the page's report, not a boolean.
        worker_created = report.get('workerCreated') == 'true'
        # The refusal's class name is a field; the sentence naming the segment is a log line, and
        # the segment is what this claim is about.
        said = ' '.join(report.get('log', []))
        conform(
            'B6',
            report.get('refused') is not None and not worker_created and 'material3' in said,
            f'workerCreated={worker_created}, refused={report.get("refused")}, '
            f'said={said[-200:]!r}',
        )

        # ---------------------------------------------------------------------------------
        # The same page, with the tier. Everything below is on this session.
        # ---------------------------------------------------------------------------------
        # `entry=material` opens the catalogue directly. Reaching it through the shell's tab bar
        # would make every claim below depend first on a claim about a chip, and a failure there
        # would read as a failure of whatever section the drill never got to.
        session = open_page(devtools, url, 'manifest=dogwood-manifest-kotlin.json&entry=material')
        if not await_first_frame(devtools, session):
            print('CONF REFUSED the page never reported a first frame', flush=True)
            return 2
        time.sleep(1.5)

        conform(
            'B6-control',
            witness(devtools, session, 'm3.section=') is not None,
            'the same manifest renders on a client that has the tier: '
            f'{witness(devtools, session, "m3.section=")}',
        )

        # M1 -- every section renders. Graded per section, so the detail names the ones that did
        # not rather than reporting a single unhelpful false.
        missing = []
        for label, evidence in SECTIONS:
            if not open_section(devtools, session, label):
                missing.append(f'{label} (chip)')
                continue
            if evidence not in names(devtools, session):
                missing.append(f'{label} ({evidence})')
        conform('M1', not missing, f'{len(SECTIONS) - len(missing)}/{len(SECTIONS)} sections; missing {missing}')

        # M2 -- a button is operable, and the payload's own state changes.
        open_section(devtools, session, 'Buttons')
        before = witness(devtools, session, 'm3.buttons=')
        activate(devtools, session, find(devtools, session, 'Filled'))
        after = await_witness(devtools, session, 'm3.buttons=', before)
        conform('M2', after is not None, f'{before} -> {after}')

        # M6 -- an icon inside a Material component announces its description.
        #
        # Graded here, before anything opens a dialog or a sheet: a modal covers the screen and
        # every claim after it would be a claim about a scrim. The icon is segment 0's and the
        # button around it is segment 255's, because Material 3's own `Icon` takes an asset and
        # cannot cross the boundary -- so the description reaching a screen reader is the claim.
        described = [n for n in ax_nodes(devtools, session)
                     if n['name'] in ('Save this', 'Bookmark this', 'Search', 'Keep', 'Done', 'Filter')]
        conform('M6', len(described) >= 3,
                f'{len(described)} icon-only controls announce a description: '
                f'{[n["name"] for n in described][:4]}')

        # M3 -- selection controls are operable and the payload's state changes.
        #
        # **The controls are found by name, and that is a correction to this drill rather than a
        # change in the client.** This claim used to take the first and second of the section's
        # *unnamed* `button` nodes, because when it was written Compose published Material 3's
        # `Checkbox`, `Switch` and `RadioButton` with no name at all. The catalogue then gave each
        # control a `contentDescription` -- recorded in plans/material3-proof.md section 5 as the
        # fix for a real user as well as for the drill -- and nobody came back to this lookup. So
        # the section's controls publish `Send me the summary`, `Background refresh` and the three
        # fare names, exactly what the Android drill has always matched on, and searching for
        # unnamed nodes found none.
        #
        # Left alone, the claim failed with "0 unnamed controls on the section" -- a red cell
        # reporting that the catalogue had been FIXED. Position among anonymous nodes was always a
        # proxy; a name is the thing itself, it says in the output which control was operated, and
        # it survives the section gaining a control. What did not change is the assertion: the
        # payload's own witness has to move, which is the claim rather than the lookup.
        #
        # `M3-announced` below is still the gap, and it is now a narrower and more useful one: these
        # publish as `button` with a name and no checked state, rather than as `checkbox`/`switch`/
        # `radio` with one.
        open_section(devtools, session, 'Selection')
        results = []
        for label, prefix in (('Send me the summary', 'm3.checkbox='),
                              ('Background refresh', 'm3.switch='),
                              ('Business', 'm3.radio=')):
            node = find(devtools, session, label)
            if node is None:
                results.append((label, prefix, 'no such control', None))
                continue
            was = witness(devtools, session, prefix)
            activate(devtools, session, node)
            results.append((label, prefix, was, await_witness(devtools, session, prefix, was)))
        conform('M3', bool(results) and all(now is not None for *_, now in results),
                f'{results}')

        # M3-announced -- and what a screen reader would be told about them.
        #
        # Skipped rather than failed, on the precedent `D7` set on Android: this is Compose's
        # accessibility bridge on this client publishing role and name only, not a defect in the
        # generated binding, and a red cell that can never go green teaches people to ignore the
        # column. The skip carries the observation so it is not mistaken for "nothing to judge".
        typed = [n for n in ax_nodes(devtools, session)
                 if n['role'] in ('checkbox', 'switch', 'radio', 'slider')]
        if typed:
            conform('M3-announced', all(
                'checked' in n['props'] or 'valuenow' in n['props'] for n in typed),
                f'{len(typed)} controls publish a role and a state')
        else:
            skipped += 1
            print('CONF M3-announced SKIP -- this client publishes Material 3\'s selection '
                  'controls as `button` nodes carrying a name but no role and no checked state: a '
                  'screen reader is told what the control is FOR but not what it is or whether it '
                  'is on. Narrower than when this was written, when they carried no name either. '
                  'Compose\'s web accessibility bridge, not the generated binding; see '
                  'tools/upstream-reports/README.md',
                  flush=True)

        # M7 -- the slider.
        #
        # The Selection section composes two, and this client publishes no accessibility node for
        # either: not an unnamed one, none at all. So there is nothing to operate through the
        # accessibility layer and nothing to read, which is a stronger statement than M3's and is
        # recorded the same way.
        sliders = [n for n in ax_nodes(devtools, session) if n['role'] == 'slider']
        if sliders:
            was = witness(devtools, session, 'm3.slider=')
            resolved = devtools.call(
                'DOM.resolveNode', {'backendNodeId': sliders[0]['backendDOMNodeId']}, session)
            object_id = resolved.get('object', {}).get('objectId')
            if object_id:
                devtools.call('Runtime.callFunctionOn', {
                    'functionDeclaration': 'function() { this.focus(); }', 'objectId': object_id,
                }, session)
                for _ in range(3):
                    for kind in ('keyDown', 'keyUp'):
                        devtools.call('Input.dispatchKeyEvent', {
                            'type': kind, 'key': 'ArrowRight', 'code': 'ArrowRight',
                            'windowsVirtualKeyCode': 39, 'nativeVirtualKeyCode': 39,
                        }, session)
                    time.sleep(0.2)
            now = await_witness(devtools, session, 'm3.slider=', was)
            conform('M7', now is not None, f'{was} -> {now}')
        else:
            skipped += 1
            print('CONF M7 SKIP -- this client publishes no accessibility node at all for a '
                  'Material 3 Slider, so there is nothing for an assistive technology to find or '
                  'move. Compose\'s web accessibility bridge; see '
                  'tools/upstream-reports/README.md', flush=True)

        # M5 -- a menu chooses, and a sheet opens.
        #
        # The menu is graded end to end on this session: it opens, an item is chosen, and the
        # payload's own state says which. The sheet gets a **fresh page**, because a modal on this
        # client takes the whole accessibility tree and nothing can be graded after one -- see M4.
        open_section(devtools, session, 'Sheets')
        was_menu = witness(devtools, session, 'm3.menu=')
        activate(devtools, session, find(devtools, session, 'Cabin class'))
        time.sleep(1.0)
        activate(devtools, session, find(devtools, session, 'Business'))
        chosen = await_witness(devtools, session, 'm3.menu=', was_menu)

        sheet_session = open_page(devtools, url, 'manifest=dogwood-manifest-kotlin.json&entry=material')
        opened = None
        if await_first_frame(devtools, sheet_session):
            time.sleep(1.5)
            open_section(devtools, sheet_session, 'Sheets')
            activate(devtools, sheet_session, find(devtools, sheet_session, 'Open the sheet'))
            deadline = time.time() + 15
            while time.time() < deadline:
                if 'Fare conditions' in names(devtools, sheet_session):
                    opened = 'Fare conditions'
                    break
                time.sleep(0.25)
        conform('M5', opened is not None and chosen is not None, f'sheet={opened}, menu={chosen}')

        # M4 -- a dialog opens and is announced.
        #
        # **A fresh page, and in two halves, and the reason is a finding rather than an ordering
        # preference.** On this client a Compose dialog takes the whole accessibility tree: while
        # one is open the tree holds its own names and nothing else, and neither a click synthesised
        # on an accessibility node, nor a mouse event dispatched at that node's own box, nor the
        # Escape key reaches it. The page is, from outside the process, stuck -- so anything graded
        # after this would fail for that reason rather than its own, and the drill gives it a page
        # of its own to be stuck on.
        #
        # What can be established is what a screen reader would be told: the dialog opened, and its
        # title, body and both buttons are in the tree. Whether activating the confirm button
        # reaches the payload is `M4-operable`, skipped here and graded on the clients whose
        # accessibility layer is the platform's own. The generated binding itself is not in doubt:
        # `Material3FamiliesTest` clicks this dialog's confirm button and reads the event on the
        # Java Virtual Machine, WebAssembly and the iOS simulator. See plans/material3-proof.md
        # section 5 and tools/upstream-reports/README.md.
        dialog_session = open_page(devtools, url, 'manifest=dogwood-manifest-kotlin.json&entry=material')
        announced = None
        if await_first_frame(devtools, dialog_session):
            time.sleep(1.5)
            open_section(devtools, dialog_session, 'Dialogs')
            activate(devtools, dialog_session, find(devtools, dialog_session, 'Open alert'))
            deadline = time.time() + 15
            while time.time() < deadline:
                current = set(names(devtools, dialog_session))
                if {'Cancel this booking?', 'Cancel booking', 'Keep it'} <= current:
                    announced = sorted(current)
                    break
                time.sleep(0.25)
        conform('M4', announced is not None, f'the dialog published {announced}')
        skipped += 1
        print('CONF M4-operable SKIP -- with a Compose dialog open this client answers no input '
              'from outside the process: not a click on the accessibility node, not a mouse event '
              'at its box, not Escape. The binding is covered by the tier\'s render tests on three '
              'targets; the platform behaviour is recorded in tools/upstream-reports/README.md',
              flush=True)

        print(f'CONF RESULT client=web passed={passed} failed={failed} skipped={skipped}', flush=True)
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
