#!/usr/bin/env python3
"""A static server that serves the brotli'd distribution, because that is what ships.

ADR-030's byte table is brotli, and a throttled measurement against uncompressed files would
measure a page nobody serves -- roughly four times the bytes over the same pipe. Precompressed
`.br` siblings are preferred whenever the client asks for them, which every browser does.

`Cache-Control: no-store` on everything: each load in the run is meant to be cold, and a cache hit
would turn a measurement into a much better-looking non-measurement.
"""
import http.server
import os
import socketserver
import sys

directory, port = sys.argv[1], int(sys.argv[2])


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

    def send_head(self):
        path = self.translate_path(self.path)
        accepts_brotli = 'br' in self.headers.get('Accept-Encoding', '')
        if accepts_brotli and os.path.isfile(path + '.br'):
            compressed = path + '.br'
            # The content type is the *uncompressed* file's -- `Content-Encoding` is a transfer
            # detail, and a `.wasm.br` served as `application/octet-stream` would defeat
            # `WebAssembly.instantiateStreaming` exactly as an unset type does.
            ctype = self.guess_type(path)
            try:
                f = open(compressed, 'rb')
            except OSError:
                return super().send_head()
            self.send_response(200)
            self.send_header('Content-Type', ctype)
            self.send_header('Content-Encoding', 'br')
            self.send_header('Content-Length', str(os.fstat(f.fileno()).st_size))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            return f
        return super().send_head()

    def end_headers(self):
        if 'Cache-Control' not in self._headers_buffer_names():
            self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def _headers_buffer_names(self):
        return [h.split(b':')[0].decode(errors='ignore')
                for h in getattr(self, '_headers_buffer', [])]

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


with Server(('127.0.0.1', port), Handler) as httpd:
    httpd.serve_forever()
