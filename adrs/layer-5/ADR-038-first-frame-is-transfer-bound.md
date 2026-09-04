# ADR-038: The Web Profile's First Frame Is Transfer-Bound

**Date:** 2026-09-03
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-030](ADR-030-web-page-weight-measured.md) measured what a Compose Multiplatform web page
weighs and then said, in its own assumptions, what it could not answer:

> **The measurement machine is not the user's.** These are byte counts, which are machine
> independent. Nothing here says anything about how long a phone takes to compile them, which is
> exactly the gap above.

That gap matters because the two candidate bottlenecks call for opposite work. If the wait is
**transfer**, the only lever is bytes, and no host-side tuning will move it. If the wait is
**WebAssembly compilation**, bytes are secondary and the work is in what the module contains and how
it is instantiated. A byte table cannot distinguish them, and neither can an unthrottled load on a
development machine — which is the only kind of load this project had run.

## 2. Decision

**Measure it, and record that the first frame is transfer-bound.**

`tools/web-ttff` drives headless Chrome over the DevTools Protocol, applying Chrome's own
`Network.emulateNetworkConditions`, and reads `firstFrameMs` from the **shipped** `index.html` —
which already publishes it as `performance.now()` inside the first `withFrameNanos`. Ten cold loads
per preset, brotli at quality 11 so the bytes on the wire are ADR-030's bytes, a fresh browser
context per load so no HTTP or compiled-module cache carries over, and nearest-rank percentiles
because interpolating between two of ten measurements invents a number nobody observed.

| preset | p50 | p95 |
|---|---|---|
| unthrottled | 135 ms | 215 ms |
| 4G — 9 Mbit/s, 85 ms round trip | 3,185 ms | 3,206 ms |
| Fast 3G — 1.6 Mbit/s, 562.5 ms round trip | **17,415 ms** | 17,469 ms |

3,103,293 bytes transferred on the render path, **identical in every load of every preset**. That
equality is the check that the throttle changed only how fast the bytes arrived and not which bytes
were asked for; a figure that drifted between presets would mean the measurement was not comparing
like with like.

## 3. Rationale & Research

**Transfer dominates, and the arithmetic says so twice.** 3.03 MiB at 1.6 Mbit/s is 15.5 seconds of
pure transfer; the measurement is 17.4. At 9 Mbit/s the same bytes need 2.76 seconds; the
measurement is 3.19. In both cases the residue — brotli decompression, WebAssembly compilation, the
first composition, everything the host actually does — is a few hundred milliseconds, and it agrees
with the unthrottled figure of 135 ms. **The connection costs roughly a hundred times what the work
costs.**

So the follow-on question ADR-030 raised is answered for this class of machine: page weight is the
only lever that matters, and the 2.5 MiB compressed WebAssembly module is where the weight is.

**The spread is a finding in its own right.** p95 is within 0.4% of p50 on both throttled presets.
There is no long tail concealing a bad case, because the bottleneck is a constant-rate pipe rather
than contention. That is also the reason ten loads is enough here and would not be enough for a
measurement whose distribution had a shape.

**Seventeen seconds is a product constraint, not a benchmark result**, and it is recorded in those
terms so nobody has to rediscover it. On the connection a large share of the world has, this profile
shows nothing for seventeen seconds. That is a legitimate reason to choose the web profile for some
products and against it for others, and it is the kind of number that should be known before the
choice rather than after.

## 4. Unstated Assumptions

- **One machine, one browser, one build.** Headless Chrome on Apple silicon. A phone pays more for
  WebAssembly compilation than this machine does, and the throttle does not touch compilation — so
  the residue above is a *floor* for the non-transfer half, not an estimate of it. On a device where
  compilation cost ten times more it would still be a small fraction of the Fast-3G figure, which is
  why the conclusion survives the gap; on the unthrottled figure it would not.
- **A shaped pipe is not a real network.** `emulateNetworkConditions` gives a constant rate and a
  fixed round trip. A real cellular connection varies, drops and re-establishes, and its p95 would
  not sit within 0.4% of its p50.
- **First frame is not first *useful* frame.** The page paints a tree it has already fetched
  everything for. A product whose first screen depends on a further data request adds that
  request's latency on top of these numbers — and on Fast 3G that request pays the 562 ms round
  trip too.
- **The measurement assumes brotli is served.** It is enforced rather than assumed: the runner
  checks `Content-Encoding: br` on a real request before it measures anything. A host that has not
  turned brotli on would see roughly four times the transfer and therefore roughly four times these
  numbers.

## 5. Updated Documents

- [ADR-030: What a Compose Multiplatform Web Page Actually Weighs](ADR-030-web-page-weight-measured.md)
  — its open assumption is answered here.
- [`tools/web-ttff/README.md`](../../tools/web-ttff/README.md) — the harness, the method, and the
  raw samples.
- [Roadmap](../../roadmap.md) — Phase 5's weight note now carries the timing beside it.
- [Technical specification](../../high-level-tech-spec-final.md) — the renderer-as-delivery-problem line now says how long the problem takes.
