#!/usr/bin/env python3
"""Serves the bridge benchmark and captures the result the page posts back.

Sets Cross-Origin-Opener-Policy and Cross-Origin-Embedder-Policy so the page is cross-origin
isolated, which is what raises Chrome's `performance.now()` resolution from 100 microseconds to 5.
Also fixes the two MIME types the Kotlin/WebAssembly output needs: without `application/wasm`,
`WebAssembly.instantiateStreaming` refuses the module.
"""
import http.server
import socketserver
import sys
import threading

directory, port, out_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
done = threading.Event()


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        '.wasm': 'application/wasm',
        '.mjs': 'text/javascript',
        '.js': 'text/javascript',
    }

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=directory, **kw)

    def end_headers(self):
        self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
        self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        with open(out_path, 'wb') as f:
            f.write(body)
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
    if not done.wait(timeout=600):
        print('TIMEOUT: page never posted a result', flush=True)
        sys.exit(1)
    print('result captured', flush=True)
