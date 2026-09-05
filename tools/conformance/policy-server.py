#!/usr/bin/env python3
"""Project Dogwood -- a witness for the network-policy drills.

The policy tests that exist are unit tests over the allow rule, and both say so: the Java Virtual
Machine one passes a client that fails if called, and the iOS one's own header admits it "tests our
logic exhaustively and does not test that NSURLSession" behaves. Neither opens a socket.

That is the gap this server closes, and it is the gap the one policy defect this project shipped
lived in -- `file://` served out of the application's own container, because the allow rule waived
"must be https" and NSURLSession then happily served a file. No amount of asserting on a lambda
finds that; only asking the real stack to make a real request does.

So: a server that records exactly which paths were requested. A refusal is proved by the *absence*
of a request here, which is a stronger claim than the client reporting that it refused -- a client
can report a refusal and still have opened the connection.

Endpoints:
  /allowed                 200, the happy path
  /redirect-to-blocked     302 to a host the drill does not permit
  /redirect-to-allowed     302 to a second path on this server
  /landing                 200, where a permitted redirect ends
  /__log                   200, the paths seen so far, newline separated
  /__reset                 200, forget them
"""
import http.server
import socketserver
import sys
import threading

PORT = int(sys.argv[1])
BLOCKED_HOST = sys.argv[2] if len(sys.argv) > 2 else "blocked.invalid"

seen: list[str] = []
lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _record(self):
        with lock:
            seen.append(self.path)

    def do_GET(self):
        if self.path == "/__log":
            with lock:
                body = ("\n".join(seen) + "\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/__reset":
            with lock:
                seen.clear()
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        self._record()

        if self.path == "/redirect-to-blocked":
            self.send_response(302)
            self.send_header("Location", f"http://{BLOCKED_HOST}:{PORT}/should-never-arrive")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if self.path == "/redirect-to-allowed":
            self.send_response(302)
            self.send_header("Location", f"/landing")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        body = b"dogwood-policy-ok"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


with Server(("0.0.0.0", PORT), Handler) as httpd:
    print(f"policy witness on {PORT}", flush=True)
    httpd.serve_forever()
