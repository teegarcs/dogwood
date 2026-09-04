#!/usr/bin/env python3
"""Does Compose Multiplatform on WebAssembly expose an accessibility tree at all?

Compose draws to a canvas through Skiko. A canvas has no intrinsic accessibility -- whatever a
screen reader reads has to be published separately, as DOM elements carrying ARIA. Whether Compose
does that, and how completely, is a question about somebody else's code, so this asks Chrome rather
than reasoning about it.

`Accessibility.getFullAXTree` returns the tree Chrome hands to assistive technology, which is the
web analogue of `UIAccessibility` and `AccessibilityNodeInfo`.
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

profile = tempfile.mkdtemp()
browser = subprocess.Popen(
    [CHROME, '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
     '--disable-extensions', '--enable-unsafe-swiftshader', '--window-size=900,700',
     '--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows',
     '--disable-renderer-backgrounding', '--force-renderer-accessibility',
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
        raise SystemExit('Chrome never opened its debugging port')

    devtools = Devtools(version['webSocketDebuggerUrl'])
    target = devtools.call('Target.createTarget', {'url': 'about:blank'})['targetId']
    session = devtools.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
    devtools.call('Runtime.enable', {}, session)
    devtools.call('Accessibility.enable', {}, session)
    devtools.call('Page.navigate', {'url': URL}, session)

    # The guest has to compose before there is anything to read.
    for _ in range(240):
        raw = devtools.call('Runtime.evaluate', {
            'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
        }, session).get('result', {}).get('value') or ''
        if raw and json.loads(raw).get('firstFrameMs'):
            break
        time.sleep(0.25)
    time.sleep(2)

    tree = devtools.call('Accessibility.getFullAXTree', {}, session)
    nodes = tree.get('nodes', [])
    print(f'PROBE {len(nodes)} accessibility nodes')
    for n in nodes[:60]:
        role = (n.get('role') or {}).get('value')
        name = (n.get('name') or {}).get('value')
        ignored = n.get('ignored')
        props = {p['name']: p['value'].get('value') for p in n.get('properties', [])}
        interesting = {k: v for k, v in props.items() if k in ('focusable', 'disabled')}
        print(f'PROBE role={role!r} name={name!r} ignored={ignored} {interesting}')

    dom = devtools.call('Runtime.evaluate', {
        'expression': "document.body.innerHTML.length + ' bytes; canvases=' "
                      "+ document.querySelectorAll('canvas').length "
                      "+ '; aria=' + document.querySelectorAll('[role],[aria-label]').length",
        'returnByValue': True,
    }, session).get('result', {}).get('value')
    print(f'PROBE dom {dom}')
finally:
    browser.terminate()
    try:
        browser.wait(timeout=10)
    except subprocess.TimeoutExpired:
        browser.kill()
    subprocess.run(['pkill', '-f', profile], check=False)
