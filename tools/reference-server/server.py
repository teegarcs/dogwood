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
  3. **Staged rollout by cohort.** `InstallCohort` gives every installation a stable bucket 0-99
     and `DogwoodDelivery` sends it as `?cohort=N`; this decides which release that bucket sees.
     Two controls, because they answer different questions. A **percentage** widens one release
     towards the whole fleet and is what an ordinary rollout is. A **cohort range** in
     `cohorts.json` pins named buckets to a named release and is what a canary is -- "buckets 0 to
     9, and nobody else, until we have looked at it". A range is the more specific statement and
     therefore wins; buckets no range names fall through to the percentage.
  4. **Resuming a previous release.** "Roll back" on this architecture is not a special mechanism:
     it is serving an earlier manifest again. One command, and the clients that quarantined the bad
     one come back on the good one.

Usage:

    tools/reference-server/server.py serve --root <dir> [--port 8080]
    tools/reference-server/server.py publish --root <dir> --from <payload-dir> --version 1.4.0
    tools/reference-server/server.py rollout --root <dir> --version 1.4.0 --percent 10
    tools/reference-server/server.py cohorts --root <dir> --range 0-9 --version 1.4.0
    tools/reference-server/server.py cohorts --root <dir> --clear
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
COHORTS = "cohorts.json"


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


def module_pool(root: pathlib.Path) -> pathlib.Path:
    """Where modules live: one flat pool shared by every release, keyed by content.

    Not inside a release directory, and that is the point. A module's address now carries the
    first sixteen hex digits of its own SHA-256 (`gradle/content-addressed-modules.gradle.kts`),
    so two releases that share an unchanged module share one file, and a canary that changes one
    module adds exactly one file. The pool is what makes `Cache-Control: immutable` true: an
    address in it can only ever hold the bytes it is named for.
    """
    return root / "modules"


def state_versions(root: pathlib.Path) -> list:
    """Every version published so far. Read fresh: `publish` is called between server lifetimes."""
    return load_state(root).get("releases", [])


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
    manifest = json.loads((source / MANIFEST).read_text())
    modules = manifest.get("modules", {})

    #     Every module address must name its own bytes, and a publish that finds one that does not is
    #     refused rather than warned about.
    #
    #     This is the gate for `gradle/content-addressed-modules.gradle.kts`. Before that step existed,
    #     every release named its module `slice-guest.zipline` -- the same string every time -- and a
    #     server holding two live releases could not tell which release a module request wanted. It
    #     guessed newest, and `cohort-drill.sh` watched a client pinned to the canary fetch the live
    #     release's bytes and refuse the load on the digest its signed manifest named. The signature
    #     caught it, which is the right direction, but the outcome is a device that cannot start.
    #
    #     Refusing here rather than warning is the difference between a property and a hope. A warning
    #     was what this used to print, and `quarantine-drill.sh` published straight past it.
    #
    unaddressed = []
    for module_id, module in sorted(modules.items()):
        url, digest = module.get("url", ""), module.get("sha256", "")
        if not digest or digest[:16] not in url:
            unaddressed.append(f"{module_id} at {url!r} (sha256 begins {digest[:16] or '?'})")
    if unaddressed:
        print(
            "this payload's module addresses do not name their bytes:\n  "
            + "\n  ".join(unaddressed)
            + "\n  Two releases live at once would both publish different bytes under one address,\n"
            "  and a module request cannot say which it wants. Build with the content-addressing\n"
            "  step (engine/gradle/content-addressed-modules.gradle.kts) and publish again.",
            file=sys.stderr,
        )
        return 1

    target.mkdir(parents=True)
    pool = module_pool(root)
    pool.mkdir(parents=True, exist_ok=True)
    module_names = {module.get("url", "") for module in modules.values()}

    for item in source.iterdir():
        if not item.is_file():
            continue
        if item.name in module_names:
            # Into the shared pool. An address that is already there must already hold these exact
            # bytes -- which content addressing makes true by construction, so a mismatch means
            # something bypassed the build step and the release is not publishable.
            existing = pool / item.name
            if existing.is_file():
                if existing.read_bytes() != item.read_bytes():
                    print(
                        f"{item.name} is already in the module pool with different bytes. An "
                        "address that names its content cannot do this; something rewrote a module "
                        "without renaming it.",
                        file=sys.stderr,
                    )
                    return 1
            else:
                shutil.copy2(item, existing)
        else:
            # The manifest, and the sample's data files beside it. Not code and not
            # content-addressed, so they stay with their release rather than joining the pool.
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
    state["cohortRanges"] = load_cohorts(root).get("ranges", [])
    print(json.dumps(state, indent=2))
    return 0


def load_cohorts(root: pathlib.Path) -> dict:
    """The bucket-range map, or an empty one.

    A separate file from `releases.json` on purpose: the ranges are a *policy* somebody writes
    during an incident or a canary, and the release list is a *fact* about what has been published.
    Mixing them would mean an operator editing a policy in the same file the publish command
    rewrites.
    """
    path = root / COHORTS
    if not path.exists():
        return {"ranges": []}
    return json.loads(path.read_text())


def save_cohorts(root: pathlib.Path, cohorts: dict) -> None:
    (root / COHORTS).write_text(json.dumps(cohorts, indent=2) + "\n")


def cohorts(root: pathlib.Path, spec: str | None, version: str | None, clear: bool) -> int:
    """Pins a range of buckets to a release, or clears every pin."""
    if clear:
        save_cohorts(root, {"ranges": []})
        print("cleared; every cohort falls through to the rollout percentage")
        return 0
    if spec is None or version is None:
        print("give --range LOW-HIGH --version V, or --clear", file=sys.stderr)
        return 1
    state = load_state(root)
    if version not in state["releases"]:
        print(f"unknown release {version}", file=sys.stderr)
        return 1
    try:
        low_text, high_text = spec.split("-", 1)
        low, high = int(low_text), int(high_text)
    except ValueError:
        print(f"--range wants LOW-HIGH, got {spec!r}", file=sys.stderr)
        return 1
    if not (0 <= low <= high <= 99):
        print(f"--range must lie inside 0-99, got {low}-{high}", file=sys.stderr)
        return 1
    book = load_cohorts(root)
    # Replaces any range with the same bounds rather than stacking a second one: a policy file that
    # accumulated duplicates would make "which release does bucket 3 see" depend on edit order.
    book["ranges"] = [r for r in book.get("ranges", []) if (r["from"], r["to"]) != (low, high)]
    book["ranges"].append({"from": low, "to": high, "version": version})
    book["ranges"].sort(key=lambda r: (r["from"], r["to"]))
    save_cohorts(root, book)
    covered = sum(r["to"] - r["from"] + 1 for r in book["ranges"])
    print(f"buckets {low}-{high} now see {version} ({covered} of 100 buckets pinned)")
    return 0


def release_for_cohort(state: dict, cohort: int | None, book: dict | None = None) -> str | None:
    """Which release this installation sees.

    Three rules, in this order, and the order is the whole design:

      1. **A range that names this bucket wins.** It is the more specific statement -- somebody
         typed "0 to 9" about a particular release -- and it is how a canary is expressed. A
         percentage cannot express one: `--percent 10` also means buckets 0 to 9, but the next
         `--percent 20` moves the boundary, and a canary that silently widened when somebody
         widened a different rollout would be the worst kind of surprise.
      2. **Otherwise the percentage**, which is an ordinary widening rollout.
      3. **Otherwise the live release**, which is what a client sending no cohort at all gets --
         every static server, and every host that has not opted in.

    Stable by construction either way: `InstallCohort` gives a device one bucket for its lifetime,
    so widening a rollout only ever ADDS devices -- nobody is moved back off a release they already
    have, which would be a downgrade nobody asked for and the one way staging can hurt more than it
    helps.
    """
    if cohort is not None:
        for entry in (book or {}).get("ranges", []):
            if entry["from"] <= cohort <= entry["to"]:
                return entry["version"]
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
            version = release_for_cohort(state, cohort, load_cohorts(self.root))
            if version is None:
                self.send_error(503, "nothing published yet")
                return
            body = (release_dir(self.root, version) / MANIFEST).read_bytes()
            # NEVER cached. The manifest is the only mutable thing here, and a cached one is a
            # fleet that cannot be updated OR rolled back -- the failure that outlasts the outage.
            self.respond(body, "application/json", cache="no-store", extra={"X-Dogwood-Release": version})
            return

        # Everything else is a module, and it is served out of the pool by the name it asks for.
        #
        # There used to be a search here -- newest release first, take whatever matched -- with a
        # long comment explaining that a module request carries no release identity and the newest
        # answer was "the least surprising rather than the correct one". That was true while every
        # release named its module `slice-guest.zipline`. It is not true now: an address carries
        # the first sixteen hex digits of its own SHA-256, written into the manifest before the
        # manifest was signed (`engine/gradle/content-addressed-modules.gradle.kts`,
        # `adrs/layer-3/ADR-077`), and `publish` refuses a payload whose addresses do not.
        #
        # So there is no guess left to make. The name in the request determines the bytes, the
        # `immutable` header below is true rather than aspirational, and two releases sharing an
        # unchanged module share one file.
        candidate = module_pool(self.root) / path
        if candidate.is_file() and candidate.resolve().is_relative_to(module_pool(self.root).resolve()):
            self.respond(
                candidate.read_bytes(),
                "application/octet-stream",
                cache="public, max-age=31536000, immutable",
            )
            return

        # Not a module: the sample's data files, which are published beside their manifest rather
        # than into the pool because they are not code and are not content-addressed. Served from
        # the live release, and that IS a choice rather than a fact -- a deployment serving assets
        # this way with two releases live has the problem the modules above no longer have, and
        # should content-address them too.
        live = state.get("live")
        if live:
            asset = release_dir(self.root, live) / path
            if asset.is_file() and asset.resolve().is_relative_to(self.root.resolve()):
                self.respond(asset.read_bytes(), "application/json", cache="no-store")
                return
        self.send_error(404, "no such module in the pool and no such asset in the live release")

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
        pinned = load_cohorts(root).get("ranges", [])
        print(f"serving {root} on :{port} -- live {state.get('live')}, "
              f"staged {state.get('staged')} at {state.get('percent', 0)}%"
              + (f", pinned {pinned}" if pinned else ""))
        server.serve_forever()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("serve", "publish", "rollout", "cohorts", "resume", "status"):
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
        if name == "cohorts":
            p.add_argument("--range", dest="spec", help="LOW-HIGH, inside 0-99")
            p.add_argument("--version")
            p.add_argument("--clear", action="store_true")
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=True)
    if args.command == "serve":
        return serve(args.root, args.port)
    if args.command == "publish":
        return publish(args.root, args.source, args.version)
    if args.command == "rollout":
        return rollout(args.root, args.version, args.percent)
    if args.command == "cohorts":
        return cohorts(args.root, args.spec, args.version, args.clear)
    if args.command == "resume":
        return resume(args.root, args.version)
    return status(args.root)


if __name__ == "__main__":
    sys.exit(main())
