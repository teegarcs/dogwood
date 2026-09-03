#!/usr/bin/env python3
"""Serves the web slice and captures what the page reports back.

Two things beyond a static file server, and both are load-bearing:

  - `application/wasm` on `.wasm`, without which `WebAssembly.instantiateStreaming` refuses the
    module outright.
  - A record of whether `guest.js` was ever fetched. That is the only way to prove from outside
    the browser that the dictionary check ran BEFORE any guest code did: on the refusal run the
    guest must never be requested, and "no tree appeared" is not evidence, because a broken guest
    looks the same.
"""
import http.server
import json
import socketserver
import sys
import threading

directory, port, out_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
done = threading.Event()
state = {'guest_fetched': False, 'guest_ran': False}


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        '.wasm': 'application/wasm',
        '.mjs': 'text/javascript',
        '.js': 'text/javascript',
        '.json': 'application/json',
    }

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=directory, **kw)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def do_GET(self):
        if self.path.startswith('/guest-ran'):
            # The beacon the guest fires as its first statement.
            state['guest_ran'] = True
            self.send_response(204)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if self.path.startswith('/guest.js'):
            # The Worker script being requested at all means the Worker was constructed.
            state['guest_fetched'] = True
        super().do_GET()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        try:
            report = json.loads(body)
        except Exception:
            report = {'parseFailure': body.decode('utf-8', 'replace')}
        report['server'] = {
            'guestScriptFetched': state['guest_fetched'],
            'guestScriptExecuted': state['guest_ran'],
        }
        with open(out_path, 'w') as f:
            json.dump(report, f, indent=2)
        self.send_response(200)
        self.send_header('Content-Length', '2')
        self.end_headers()
        self.wfile.write(b'ok')
        done.set()

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


with Server(('127.0.0.1', port), Handler) as httpd:
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print(f'serving {directory} on http://127.0.0.1:{port}', flush=True)
    if not done.wait(timeout=180):
        print('TIMEOUT: the page never posted a report', flush=True)
        sys.exit(1)
    print('report captured', flush=True)
