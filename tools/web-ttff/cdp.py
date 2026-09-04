#!/usr/bin/env python3
"""Project Dogwood -- time to first frame, under a throttled network.

ADR-030 measured what the web profile *weighs*. Bytes are not latency: what a person waits for is
the first frame, and that depends on the connection carrying those bytes, on decompression, and on
WebAssembly compilation -- none of which a byte table shows.

This drives a real headless Chrome over the Chrome DevTools Protocol so the throttling is the
browser's own (`Network.emulateNetworkConditions`), not a simulation of it in a Python server. The
page under test is the **shipped** `index.html`, uninstrumented: it already publishes
`globalThis.__dogwoodReport`, whose `firstFrameMs` is `performance.now()` inside the first
`withFrameNanos` -- measured from navigation start, by the page, in the page's own clock.

The DevTools Protocol speaks WebSocket, and this repository has no WebSocket library, so there is a
small RFC 6455 client below. It is about eighty lines because it only has to do one thing: send
short text frames and read the replies from a server that is on the same machine and is not hostile.
"""
import base64
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request

# ---------------------------------------------------------------------------------------------
# A WebSocket client, small enough to read.
# ---------------------------------------------------------------------------------------------


class WebSocket:
    """Text frames over a raw socket. Client frames are masked, as the standard requires."""

    def __init__(self, url, timeout=180):
        assert url.startswith('ws://'), url
        rest = url[len('ws://'):]
        hostport, _, path = rest.partition('/')
        host, _, port = hostport.partition(':')
        self.sock = socket.create_connection((host, int(port or 80)), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            f'GET /{path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n'
            f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'
        ).encode())
        header = b''
        while b'\r\n\r\n' not in header:
            chunk = self.sock.recv(1)
            if not chunk:
                raise RuntimeError('the debugger closed during the handshake')
            header += chunk
        if b'101' not in header.split(b'\r\n')[0]:
            raise RuntimeError(f'handshake refused: {header[:120]!r}')
        self.buffer = b''

    def _recv_exactly(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError('the debugger closed the connection')
            self.buffer += chunk
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def send(self, text):
        payload = text.encode()
        header = bytearray([0x81])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 1 << 16:
            header.append(0x80 | 126)
            header += struct.pack('>H', length)
        else:
            header.append(0x80 | 127)
            header += struct.pack('>Q', length)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv(self):
        """One complete message, reassembling continuations and answering pings."""
        pieces = []
        while True:
            first, second = self._recv_exactly(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack('>H', self._recv_exactly(2))[0]
            elif length == 127:
                length = struct.unpack('>Q', self._recv_exactly(8))[0]
            payload = self._recv_exactly(length) if length else b''
            if opcode == 0x9:              # ping
                self.sock.sendall(b'\x8a' + bytes([0x80 | len(payload)]) + os.urandom(4) + payload)
                continue
            if opcode == 0x8:              # close
                raise RuntimeError('the debugger sent a close frame')
            pieces.append(payload)
            if first & 0x80:               # final fragment
                return b''.join(pieces).decode()

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------------------------------------
# The DevTools Protocol, only as much of it as this needs.
# ---------------------------------------------------------------------------------------------


class Devtools:
    def __init__(self, ws_url):
        self.ws = WebSocket(ws_url)
        self.next_id = 0

    def call(self, method, params=None, session=None):
        self.next_id += 1
        message = {'id': self.next_id, 'method': method, 'params': params or {}}
        if session:
            message['sessionId'] = session
        self.ws.send(json.dumps(message))
        while True:
            reply = json.loads(self.ws.recv())
            # Events and other sessions' replies stream over the same socket; ignore anything
            # that is not the answer to this call.
            if reply.get('id') != message['id']:
                continue
            if 'error' in reply:
                raise RuntimeError(f"{method} failed: {reply['error']}")
            return reply.get('result', {})


# Chrome DevTools' own numbers, written out rather than named, because the label has moved between
# Chrome versions ("Fast 3G" became "Slow 4G") while the numbers did not.
PRESETS = {
    'none': None,
    'fast-3g': {'latency': 562.5, 'downloadThroughput': 1.6e6 / 8, 'uploadThroughput': 750e3 / 8},
    '4g': {'latency': 85.0, 'downloadThroughput': 9e6 / 8, 'uploadThroughput': 3.75e6 / 8},
}


def measure(url, preset, loads, chrome, port):
    profile = tempfile.mkdtemp()
    browser = subprocess.Popen(
        [chrome, '--headless=new', '--no-sandbox', '--no-first-run', '--no-default-browser-check',
         '--disable-extensions', '--enable-unsafe-swiftshader', '--window-size=900,700',
         '--disable-background-timer-throttling', '--disable-backgrounding-occluded-windows',
         '--disable-renderer-backgrounding',
         f'--remote-debugging-port={port}', f'--user-data-dir={profile}', 'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
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
            raise RuntimeError('Chrome never opened its debugging port')

        devtools = Devtools(version['webSocketDebuggerUrl'])
        samples = []
        bytes_seen = []
        for attempt in range(loads):
            # A fresh browser context per load, which is what makes each one cold: a new context
            # has its own empty HTTP cache and its own empty compiled-WebAssembly cache. Reusing
            # one target and reloading would measure a warm start after the first iteration and
            # would look like a dramatic improvement rather than a mistake.
            context = devtools.call('Target.createBrowserContext')['browserContextId']
            target = devtools.call(
                'Target.createTarget', {'url': 'about:blank', 'browserContextId': context},
            )['targetId']
            session = devtools.call(
                'Target.attachToTarget', {'targetId': target, 'flatten': True},
            )['sessionId']

            devtools.call('Network.enable', {}, session)
            devtools.call('Network.setCacheDisabled', {'cacheDisabled': True}, session)
            if preset:
                devtools.call('Network.emulateNetworkConditions',
                              {'offline': False, **preset}, session)
            devtools.call('Runtime.enable', {}, session)
            devtools.call('Page.navigate', {'url': url}, session)

            first_frame = None
            deadline = time.time() + 180
            while time.time() < deadline:
                result = devtools.call('Runtime.evaluate', {
                    'expression': 'globalThis.__dogwoodReport || ""',
                    'returnByValue': True,
                }, session)
                raw = result.get('result', {}).get('value') or ''
                if raw:
                    try:
                        report = json.loads(raw)
                    except json.JSONDecodeError:
                        report = {}
                    if report.get('firstFrameMs'):
                        first_frame = int(report['firstFrameMs'])
                        break
                time.sleep(0.25)

            # Bytes on the wire, read from the page's own Resource Timing entries rather than
            # from the staged file sizes. Those differ: the source map and the refusal-path
            # manifest are staged but never fetched, so a directory total overstates what a person
            # actually waits for. Measuring it per load also catches the opposite mistake -- a
            # figure that quietly changes between presets would mean the throttle had altered what
            # was fetched rather than only how fast.
            transferred = None
            if first_frame is not None:
                measured = devtools.call('Runtime.evaluate', {
                    'expression': "performance.getEntriesByType('resource')"
                                  ".reduce((t, e) => t + (e.transferSize || 0), 0)",
                    'returnByValue': True,
                }, session).get('result', {}).get('value')
                if isinstance(measured, (int, float)):
                    transferred = int(measured)

            devtools.call('Target.closeTarget', {'targetId': target})
            devtools.call('Target.disposeBrowserContext', {'browserContextId': context})
            if first_frame is None:
                print(f'  load {attempt + 1}: no frame within 180 s', file=sys.stderr)
            else:
                samples.append(first_frame)
                if transferred is not None:
                    bytes_seen.append(transferred)
                print(f'  load {attempt + 1}: {first_frame} ms'
                      + (f', {transferred // 1024} KiB transferred' if transferred else ''))
        devtools.ws.close()
        return samples, bytes_seen
    finally:
        browser.terminate()
        try:
            browser.wait(timeout=10)
        except subprocess.TimeoutExpired:
            browser.kill()
        subprocess.run(['pkill', '-f', profile], check=False)


def percentile(values, fraction):
    """Nearest-rank, which is the honest choice for ten samples.

    Interpolating between two of ten measurements invents a number that was never observed; at
    this sample size the rank is the whole story.
    """
    ordered = sorted(values)
    if not ordered:
        return None
    index = max(0, min(len(ordered) - 1, int(round(fraction * len(ordered) + 0.5)) - 1))
    return ordered[index]


def main():
    url, chrome, port, loads = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    out = sys.argv[5]
    results = {}
    for name in ('none', '4g', 'fast-3g'):
        print(f'== {name} ==')
        samples, bytes_seen = measure(url, PRESETS[name], loads, chrome, port)
        port += 1
        results[name] = {
            'samples': samples,
            'transferredBytes': bytes_seen,
            'medianTransferredBytes': percentile(bytes_seen, 0.50),
            'loads': loads,
            'completed': len(samples),
            'p50': percentile(samples, 0.50),
            'p95': percentile(samples, 0.95),
            'min': min(samples) if samples else None,
            'max': max(samples) if samples else None,
        }
        print(f"  p50 {results[name]['p50']} ms   p95 {results[name]['p95']} ms   "
              f"({len(samples)}/{loads} loads)")
    with open(out, 'w') as f:
        json.dump(results, f, indent=2)
    print(f'\nwrote {out}')
    # A preset that never produced a frame is a failed measurement, not a slow one.
    return 0 if all(r['completed'] for r in results.values()) else 1


if __name__ == '__main__':
    sys.exit(main())
