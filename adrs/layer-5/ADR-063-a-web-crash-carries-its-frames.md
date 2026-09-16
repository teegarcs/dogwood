# ADR-063: A web crash carries its frames

> **2026-09-15.** The Worker is now constructed from a `Blob` of the verified script bytes
> (ADR-062's integrity check), so a crash's frames name `blob:http://…/<uuid>:line:column` rather
> than `guest-kotlin.js:line:column`. The offsets are the same offsets into the same bundle;
> `tools/symbolicate/resolve.py` parses any `file:line:column` and never keyed on the name. What
> identifies the build is the release identity the page reports, which since the same change is the
> script's digest — a better key than a file name, because it names the bytes. The web drill's
> frame count keyed on the file name and reported zero frames on a crash that carried them; it now
> counts `:line:column` positions.

**Date:** 2026-09-08
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-059](ADR-059-a-guest-crash-a-host-can-read.md) fixed a guest crash that reached no host at all,
and recorded a remainder: the Zipline path carries a source-mapped stack, and the Worker path
carried one sentence. A payload ships without store review, so a bad one ships fast, and the
difference between "something threw in the guest" and "line 68 of `CrashScreen.kt` threw" is the
difference between an afternoon and a week.

Three things had to be settled, in this order, and the plan said the claim should go whichever way
the evidence did:

1. What does a **production** webpack build actually leave on a thrown error?
2. Can whatever that is be routed through `onGuestError` without an envelope revision?
3. Does it survive to the host when a real guest crashes in a real browser?

## 2. Decision

**The guest sends its stack, the host reports it separately from the message, and symbolication is
an offline step against a source map the build keeps and does not serve.**

- `CrashScreen.kt` in `samples/slice-screens` is a payload that renders a marker, lets a frame be
  applied, and then throws from a `LaunchedEffect`. It is reachable as entry point `crash` on both
  the mobile and web guests.
- The guest encodes a failure as `message`, a blank line, then the stack. The host splits on the
  first blank line. A payload that sends no stack produces exactly the string this path always
  produced.
- `WorkerBridgeListener.onGuestFailure(correlation, message, stack)` is added **with a default**
  that drops the stack and calls `onGuestError`, so no existing host has to change.
- `tools/symbolicate/resolve.py` resolves a minified stack against `guest-kotlin.js.map`.
- The source map stays a **build artefact**. `samples/web-slice/build.gradle.kts` copies
  `guest-kotlin.js` into the distribution and not the map.
- Graded as `A4-web`, `A4-web-stack` and `A4-web-control` in `tools/conformance/web_services.py`.

## 3. Rationale & Research

**What a production build preserves — measured, not assumed.** A real crash, in headless Chrome,
against the production distribution:

```
guest error: uncaught in guest: dogwood deliberate guest crash: CRASH-COMPOSED
guest stack: bs: dogwood deliberate guest crash: CRASH-COMPOSED
    at bn.p8 (http://127.0.0.1:8811/guest-kotlin.js:1:419445)
    at er.o8 (http://127.0.0.1:8811/guest-kotlin.js:1:90351)
    ... 8 more
```

Names are mangled — `bn.p8` is not something anyone can act on. **Offsets are exact**, and offsets
are what a source map resolves. So the frames are worth carrying, and the resolution is worth doing
somewhere other than the browser. Ten frames arrive; the drill requires at least three.

Resolved against the map the build already produces:

```
at bn.p8 .../slice-screens/src/jsMain/kotlin/dev/dogwood/slice/CrashScreen.kt:71:436
   <- not a position in this file (70 lines); nearest real mapping 68:11
at er.o8 .../kotlin/coroutines/CoroutineImpl.kt:44:35
at yr   .../kotlinx-coroutines-core/common/src/internal/DispatchedContinuation.kt:237:26
```

Line 68 is the `error(...)` call. Every frame resolves.

**Why the map is not served.** A public source map hands every reader the payload's Kotlin source.
That is a real cost with no operational benefit: nothing in the browser needs the map, because
nothing in the browser symbolicates. Keeping it a build artefact is what makes the stack safe to
send.

**Why two lines of a string rather than a typed message.** The `ERROR` envelope's payload is a
string on every path. Widening it to carry a structured failure means an envelope revision, which
every guest and host in the fleet must then agree on — for a diagnostic. Splitting on the first
blank line costs nothing and keeps old payloads working unchanged.

**Why the fixture throws from an effect and renders first.** A throw during composition is caught by
the composition and surfaces as a failed frame; a throw from a coroutine the guest launched is the
one that used to vanish, because it landed on the effect's `CoroutineContext` and there was no
handler on it. And a screen that threw before drawing would make "the host saw a crash"
indistinguishable from "the payload never ran" — the exact confusion a crash report exists to
resolve. So it renders `CRASH-COMPOSED`, a frame goes out, and then it fails, and the drill asserts
both halves.

**A finding, produced by looking.** The symbolicator's first run reported `CrashScreen.kt:71:436` for
the top frame — a position in a 70-line file whose longest line is nowhere near 436 characters. The
resolver was correct; the *map* contains that entry, emitted for code Kotlin/JavaScript synthesised
from `error(...)`. The mapping six bytes further into the bundle points at 68:11, which is the call.
Rather than silently substituting the plausible answer — inventing one — or dropping the frame —
throwing away the only one anybody cares about — the tool reports the specification's answer and
names the nearest in-range mapping beside it. Drafted as upstream report #4, unfiled.

## 4. Unstated Assumptions

- **That `.stack` exists on a Kotlin/JavaScript `Throwable`.** It does, because they are backed by
  JavaScript `Error` objects; read defensively through a `js(...)` guard, so a runtime that did not
  provide one yields no stack rather than an exception inside the crash handler.
- **That the stack is not sensitive.** Frames name the bundle and offsets, not user data. The
  *message* can contain whatever a payload put in an exception, which was already true before this.
- **That a deployment keeps its maps.** Symbolication needs the map for the exact build that
  crashed. A pipeline that discards build artefacts gets frames it cannot resolve — which is still
  strictly more than the one sentence it had before.
- **That out-of-range mappings are a Kotlin/JavaScript artefact rather than a bug in the decoder.**
  Checked by decoding the segments around the frame by hand and reading the map's own
  `sourcesContent`: the entry is in the map.

## 5. Updated Documents

- [Layer 5: Host Runtime and Session Management](../../specs/layer-5-host.md)
- [Conformance plan](../../plans/conformance.md) — `A4`'s web evidence
- [Engineering backlog](../../plans/engineering-backlog.md) — S3 marked done
- [Checks](../../docs/checks.md)
- [`tools/symbolicate/resolve.py`](../../tools/symbolicate/resolve.py)
- [`tools/upstream-reports/README.md`](../../tools/upstream-reports/README.md) — draft #4
