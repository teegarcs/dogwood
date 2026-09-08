#!/usr/bin/env python3
"""Project Dogwood -- the reference payload server.

The adoption audit's A2/A5 neighbours and the framework grade both named the same gap: the
machinery to *publish* a payload existed and the machinery to *operate* one did not. Payloads were
served by a Gradle task on `localhost:8080`, and `docs/operating.md` §5 described a real deployment
rather than pointing at one. This is the thing it now points at.

**It is a reference, not a product**, and the distinction is load-bearing: it is one file, it
stores releases on disk, and it holds no opinion about your infrastructure. What it does hold is
the four behaviours that are easy to get wrong and expensive to get wrong later, each implemented
here so a real deployment has something to copy and to diff against:

  1. **The cache split.** Payload files are content-addressed and immutable; the *manifest* is not
     and must never be cached. Getting this backwards gives you either stale clients or no caching
     at all, and neither announces itself.
  2. **Brotli, when the client asks.** Serving gzip to a browser that offered brotli costs 27% and
     about five seconds on a slow connection (ADR-045), silently.
  3. **Staged rollout by cohort.** `InstallCohort` gives every installation a stable bucket 0-99;
     the client sends it, and this decides which release that bucket sees. Rollout is a server
     decision -- the client half has existed since ADR-049 with nothing on the other end.
  4. **Resuming a previous release.** "Roll back" on this architecture is not a special mechanism:
     it is serving an earlier manifest again. One command, and the clients that quarantined the bad
     one come back on the good one.

Usage:

    tools/reference-server/server.py serve --root <dir> [--port 8080]
    tools/reference-server/server.py publish --root <dir> --from <payload-dir> --version 1.4.0
    tools/reference-server/server.py rollout --root <dir> --version 1.4.0 --percent 10
    tools/reference-server/server.py resume  --root <dir> --version 1.3.0
    tools/reference-server/server.py status  --root <dir>

`publish` takes a directory that a Zipline build already produced -- `manifest.zipline.json` plus
its `.zipline` modules, already **signed**, because signing belongs to the build where the key is,
not to the server where it would have to live.
"""
import argparse
import gzip
import hashlib
import http.server
import json
import pathlib
import shutil
import socketserver
import sys
import time

try:
    import brotli  # type: ignore
    HAVE_BROTLI = True
except ImportError:
    HAVE_BROTLI = False

MANIFEST = "manifest.zipline.json"
STATE = "releases.json"


# ---------------------------------------------------------------------------------------------
# The store: releases on disk, plus which one each cohort sees.
# ---------------------------------------------------------------------------------------------

def load_state(root: pathlib.Path) -> dict:
    path = root / STATE
    if not path.exists():
        return {"releases": [], "live": None, "staged": None, "percent": 0}
    return json.loads(path.read_text())


def save_state(root: pathlib.Path, state: dict) -> None:
    (root / STATE).write_text(json.dumps(state, indent=2) + "\n")


def release_dir(root: pathlib.Path, version: str) -> pathlib.Path:
    return root / "releases" / version


def publish(root: pathlib.Path, source: pathlib.Path, version: str) -> int:
    """Copies a built, signed payload in and makes it the staged release at 0%.

    Deliberately does NOT make it live. A publish that went straight to every device would make
    the rollout controls decorative, and the whole point of them is that a bad release meets a
    hundredth of your users rather than all of them.
    """
    if not (source / MANIFEST).exists():
        print(f"no {MANIFEST} in {source} -- build and sign the payload first", file=sys.stderr)
        return 1
    target = release_dir(root, version)
    if target.exists():
        # Immutability is the property the cache headers below promise. A republished version
        # would be a lie told to every client that already cached it.
        print(f"release {version} already exists; publish a new version", file=sys.stderr)
        return 1
    target.mkdir(parents=True)
    for item in source.iterdir():
        if item.is_file():
            shutil.copy2(item, target / item.name)

    state = load_state(root)
    state["releases"] = sorted(set(state["releases"] + [version]))
    state["staged"] = version
    state["percent"] = 0
    if state["live"] is None:
        # The first release has nothing to be staged against.
        state["live"] = version
        state["percent"] = 100
    save_state(root, state)
    digest = hashlib.sha256((target / MANIFEST).read_bytes()).hexdigest()[:16]
    print(f"published {version} ({digest}) -- staged at 0%; `rollout` to widen it")
    return 0


def rollout(root: pathlib.Path, version: str, percent: int) -> int:
    state = load_state(root)
    if version not in state["releases"]:
        print(f"unknown release {version}", file=sys.stderr)
        return 1
    state["staged"] = version
    state["percent"] = max(0, min(100, percent))
    if state["percent"] == 100:
        state["live"] = version
    save_state(root, state)
    print(f"{version} now serves {state['percent']}% of cohorts (live: {state['live']})")
    return 0


def resume(root: pathlib.Path, version: str) -> int:
    """Serves an earlier release again -- the whole of what 'roll back' means here.

    The clients that quarantined the bad release are not waiting for a signal: they are refusing a
    *version*, and a different version is not refused. So this needs no client cooperation at all,
    which is why it is one line of server state rather than a protocol.
    """
    state = load_state(root)
    if version not in state["releases"]:
        print(f"unknown release {version}", file=sys.stderr)
        return 1
    state["live"] = version
    state["staged"] = version
    state["percent"] = 100
    save_state(root, state)
    print(f"resumed {version} for every cohort")
    return 0


def status(root: pathlib.Path) -> int:
    state = load_state(root)
    print(json.dumps(state, indent=2))
    return 0


def release_for_cohort(state: dict, cohort: int | None) -> str | None:
    """Which release this installation sees.

    Buckets below the percentage get the staged release. Stable by construction: `InstallCohort`
    gives a device one bucket for its lifetime, so widening a rollout only ever ADDS devices --
    nobody is moved back off a release they already have, which would be a downgrade nobody asked
    for and the one way a staged rollout can hurt more than it helps.
    """
    if state.get("staged") and cohort is not None and cohort < state.get("percent", 0):
        return state["staged"]
    return state.get("live")


# ---------------------------------------------------------------------------------------------
# The server.
# ---------------------------------------------------------------------------------------------

class Handler(http.server.BaseHTTPRequestHandler):
    root: pathlib.Path

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.address_string()} {fmt % args}\n")

    def do_GET(self):  # noqa: N802 -- the base class names it
        path = self.path.split("?", 1)[0].lstrip("/")
        query = self.path.split("?", 1)[1] if "?" in self.path else ""
        state = load_state(self.root)

        if path in ("", MANIFEST):
            cohort = None
            for part in query.split("&"):
                if part.startswith("cohort="):
                    try:
                        cohort = int(part.split("=", 1)[1])
                    except ValueError:
                        cohort = None
            version = release_for_cohort(state, cohort)
            if version is None:
                self.send_error(503, "nothing published yet")
                return
            body = (release_dir(self.root, version) / MANIFEST).read_bytes()
            # NEVER cached. The manifest is the only mutable thing here, and a cached one is a
            # fleet that cannot be updated OR rolled back -- the failure that outlasts the outage.
            self.respond(body, "application/json", cache="no-store", extra={"X-Dogwood-Release": version})
            return

        # Everything else is a module, addressed by content and therefore immutable forever.
        for version in state["releases"]:
            candidate = release_dir(self.root, version) / path
            if candidate.is_file() and candidate.resolve().is_relative_to(self.root.resolve()):
                self.respond(
                    candidate.read_bytes(),
                    "application/octet-stream",
                    cache="public, max-age=31536000, immutable",
                )
                return
        self.send_error(404, "no such module in any published release")

    def respond(self, body: bytes, content_type: str, cache: str, extra: dict | None = None):
        accepted = self.headers.get("Accept-Encoding", "")
        encoding = None
        if HAVE_BROTLI and "br" in accepted:
            body, encoding = brotli.compress(body), "br"
        elif "gzip" in accepted:
            body, encoding = gzip.compress(body), "gzip"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        if encoding:
            self.send_header("Content-Encoding", encoding)
        self.send_header("Vary", "Accept-Encoding")
        for name, value in (extra or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)


def serve(root: pathlib.Path, port: int) -> int:
    if not HAVE_BROTLI:
        # Loud, because the cost is invisible: a client that asked for brotli and got gzip pays
        # 27% and about five seconds on a slow link, and nothing on the device can tell.
        print("WARNING: the `brotli` module is not installed; serving gzip instead", file=sys.stderr)
    Handler.root = root
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("127.0.0.1", port), Handler) as server:
        state = load_state(root)
        print(f"serving {root} on :{port} -- live {state.get('live')}, "
              f"staged {state.get('staged')} at {state.get('percent', 0)}%")
        server.serve_forever()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("serve", "publish", "rollout", "resume", "status"):
        p = sub.add_parser(name)
        p.add_argument("--root", required=True, type=pathlib.Path)
        if name == "serve":
            p.add_argument("--port", type=int, default=8080)
        if name == "publish":
            p.add_argument("--from", dest="source", required=True, type=pathlib.Path)
            p.add_argument("--version", required=True)
        if name in ("rollout", "resume"):
            p.add_argument("--version", required=True)
        if name == "rollout":
            p.add_argument("--percent", type=int, required=True)
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=True)
    if args.command == "serve":
        return serve(args.root, args.port)
    if args.command == "publish":
        return publish(args.root, args.source, args.version)
    if args.command == "rollout":
        return rollout(args.root, args.version, args.percent)
    if args.command == "resume":
        return resume(args.root, args.version)
    return status(args.root)


if __name__ == "__main__":
    sys.exit(main())
