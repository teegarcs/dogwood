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

        # ---------------------------------------------------------------------------------
        # H4/H5 -- a publisher can stop a release, and the guest never runs.
        #
        # The web was the last unguarded client (`plans/adoption-audit.md` A3's remainder). The
        # assertion is about ABSENCE done properly: not "the screen is empty", which a broken page
        # also satisfies, but that the page reports a refusal by name AND never created a Worker --
        # `workerCreated=false` is the page's own record of the thing not happening.
        # ---------------------------------------------------------------------------------
        devtools.call('Page.navigate', {
            'url': f'{url}?manifest=dogwood-manifest-disabled.json'}, session)
        refused = {}
        for _ in range(120):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or ''
            if raw:
                refused = json.loads(raw)
                if refused.get('refused'):
                    break
            time.sleep(0.25)
        conform(
            'H4',
            refused.get('refused') == 'ReleaseRefused' and refused.get('workerCreated') != 'true',
            f"refused={refused.get('refused')} workerCreated={refused.get('workerCreated')}",
        )
        conform(
            'H5',
            any('refused' in line for line in refused.get('log', [])),
            f"the page said why: {[l for l in refused.get('log', []) if 'refus' in l][:2]}",
        )

        # ---------------------------------------------------------------------------------
        # B1 and B2 -- the sidecar is signed, and a client that holds keys will not believe an
        # unsigned or altered one.
        #
        # These claims were previously graded for the web by `dev.dogwood.host.SignatureTest`, a
        # Java Virtual Machine test of the *mobile* verifier, which is evidence about a code path
        # this client does not run. Since ADR-062 the web has a verifier of its own and it can be
        # graded the way everything else here is: on a real browser, against real bytes.
        #
        # The fixtures are the build's own signed output with one thing changed each; `run-web.sh`
        # derives them. Deriving rather than re-signing matters -- a fixture from a second signer
        # would grade whether two signers agree.
        # ---------------------------------------------------------------------------------
        def load(query):
            """Navigates and returns the page's report, or {} if it never finished."""
            devtools.call('Page.navigate', {'url': f'{url}?{query}'}, session)
            for _ in range(160):
                raw = devtools.call('Runtime.evaluate', {
                    'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
                }, session).get('result', {}).get('value') or ''
                if raw:
                    report = json.loads(raw)
                    if report.get('done') or report.get('refused'):
                        return report
                time.sleep(0.25)
            return {}

        # The control, and every claim below is worthless without it: the *signed* manifest, on a
        # page holding keys, still starts. Without this a refusal proves only that the page is
        # broken, which is a state that satisfies every negative assertion here.
        good = load('manifest=dogwood-manifest-kotlin.json')
        conform(
            'B1-control',
            good.get('workerCreated') == 'true' and not good.get('refused'),
            f"the signed sidecar was accepted: workerCreated={good.get('workerCreated')} "
            f"refused={good.get('refused')}",
        )

        # B1 -- the signature covers the bytes. The fixture alters `guestScript`, which is the field
        # an attacker would actually want: a sidecar that can be rewritten can point the page at any
        # script on the origin. The real signature is served beside it unchanged.
        tampered = load('manifest=dogwood-manifest-tampered.json')
        conform(
            'B1',
            tampered.get('refused') == 'SignatureRefused'
            and tampered.get('workerCreated') != 'true',
            f"refused={tampered.get('refused')} workerCreated={tampered.get('workerCreated')} -- "
            f"{[l for l in tampered.get('log', []) if 'refus' in l][:1]}",
        )

        # And a missing signature is a refusal too, which is the reading that makes the check worth
        # having: an attacker who can replace a manifest can delete the file beside it.
        unsigned = load('manifest=dogwood-manifest-unsigned.json')
        conform(
            'B1-absent',
            unsigned.get('refused') == 'SignatureRefused'
            and unsigned.get('workerCreated') != 'true',
            f"refused={unsigned.get('refused')} workerCreated={unsigned.get('workerCreated')}",
        )

        # B5 -- the script's bytes are covered, not only its address. The fixture's signature is
        # VALID; its digest is wrong by construction, which is what a script swapped at the origin
        # after signing looks like from the client. `IntegrityRefused` rather than `SignatureRefused`
        # is the point: the sidecar was believed, and the bytes it named were not.
        swapped = load('manifest=dogwood-manifest-tampered-script.json')
        conform(
            'B5',
            swapped.get('refused') == 'IntegrityRefused'
            and swapped.get('workerCreated') != 'true',
            f"refused={swapped.get('refused')} workerCreated={swapped.get('workerCreated')} -- "
            f"{[l for l in swapped.get('log', []) if 'hash to' in l][:1]}",
        )

        # B2 -- rotation. The fixture is signed by the key being rotated *to*, alone. A client
        # holding both keys must accept it, or a rotation would be an outage rather than a
        # roll-forward: publishers sign with both, clients move, then the old key is retired.
        rotated = load('manifest=dogwood-manifest-rotated.json')
        conform(
            'B2',
            rotated.get('workerCreated') == 'true' and not rotated.get('refused'),
            f"a sidecar signed only by the rotation key was accepted: "
            f"workerCreated={rotated.get('workerCreated')} refused={rotated.get('refused')}",
        )

        # The written-decision control. `emptyMap()` means "believe the origin", which is what every
        # web host did before ADR-062, and the drill grades that it still works -- because the point
        # of making it explicit was never to remove it. Read together with `B1`, this pins the
        # refusals above to the signature check rather than to anything else about the fixture.
        #
        # The UNSIGNED fixture, not the tampered one, since 2026-09-15. The tampered fixture alters
        # `guestScript` and keeps the digest of the script it no longer names, so it is refused for
        # its digest whatever the key posture -- and that is a property of the integrity check (B5),
        # not of the signature posture this control exists to pin. The unsigned fixture is the same
        # consistent manifest with its signature file absent: exactly "no keys, no signature".
        unchecked = load('manifest=dogwood-manifest-unsigned.json&trust=none')
        conform(
            'B1-unsigned-posture',
            unchecked.get('workerCreated') == 'true',
            "a host that passes no trusted keys still runs an unsigned but consistent sidecar: "
            f"workerCreated={unchecked.get('workerCreated')} refused={unchecked.get('refused')}",
        )

        # ---------------------------------------------------------------------------------
        # A4 -- a guest crash reaches the host, with frames.
        #
        # ADR-059 fixed the routing and recorded a remainder: the Zipline path carries a
        # source-mapped stack and the Worker path carried one sentence. This grades the fix on the
        # thing itself -- a real payload that throws from a real effect in a real browser -- because
        # the defect ADR-059 found was that the crash was not merely unreadable but *unobservable*,
        # and only a real crash could have shown that.
        #
        # `entry=crash` is `CrashScreen.kt`: it renders a marker, a frame is applied, and then an
        # effect throws. Rendering first is what makes the two assertions separable -- a payload that
        # never ran would satisfy "the host saw no widgets" without saying anything about crashes.
        # ---------------------------------------------------------------------------------
        crashed = load('manifest=dogwood-manifest-kotlin.json&entry=crash')
        crash_log = crashed.get('log', [])
        # Give the effect its delay plus the round trip; the screen composes well before it fails.
        for _ in range(60):
            raw = devtools.call('Runtime.evaluate', {
                'expression': 'globalThis.__dogwoodReport || ""', 'returnByValue': True,
            }, session).get('result', {}).get('value') or ''
            if raw:
                crash_log = json.loads(raw).get('log', [])
                if any('guest error' in line for line in crash_log):
                    break
            time.sleep(0.25)

        error_line = next((l for l in crash_log if 'guest error' in l), '')
        stack_line = next((l for l in crash_log if l.startswith('guest stack:')), '')

        # The control: the screen composed before it failed. Without it, "the host reported a
        # crash" could be satisfied by a payload that never started.
        conform(
            'A4-web-control',
            'CRASH-COMPOSED' in ' '.join(n['name'] or '' for n in ax_nodes(devtools, session))
            or 'CRASH-COMPOSED' in error_line,
            'the crashing screen rendered before it threw',
        )

        # The message crosses, intact. The fixture's string is distinctive on purpose: a host that
        # reports something else is not carrying what it thinks it is.
        conform(
            'A4-web',
            'dogwood deliberate guest crash' in error_line,
            error_line or f'no guest error reached the host: {crash_log[-3:]}',
        )

        # And the frames cross with it. They are minified -- `bn.p8`, not a function name -- and that
        # is expected and stated: what a production webpack build preserves is exact line and column
        # offsets into the bundle, which `tools/symbolicate/resolve.py` turns back into Kotlin files
        # and lines against the source map the build keeps and does not serve. See ADR-063.
        # Counted as `:line:column` positions rather than by the script's file name: the Worker is
        # built from a Blob of the verified bytes (B5), so a frame reads `blob:http://…/<uuid>:1:419445`
        # and the file name is gone. The offsets are the same offsets into the same bundle, which is
        # all the symbolicator ever used; keying on the name reported zero frames on a crash that
        # carried ten (2026-09-15).
        frames = len(re.findall(r':\d+:\d+\)?(?:\s|$)', stack_line))
        conform(
            'A4-web-stack',
            frames >= 3,
            f'{frames} frames arrived with the crash: {stack_line[:180]}'
            if stack_line else 'the crash arrived with no stack at all',
        )

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
