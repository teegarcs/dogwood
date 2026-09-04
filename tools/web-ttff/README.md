# Time to first frame, on a connection somebody actually has

[ADR-030](../../adrs/layer-5/ADR-030-web-page-weight-measured.md) measured what the web profile
**weighs**. Bytes are not latency. What a person waits for is the first frame, and that depends on
the connection carrying those bytes, on brotli decompression, and on WebAssembly compilation — none
of which a byte table shows.

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
tools/web-ttff/run.sh          # 10 cold loads per preset
```

## How it is measured

- **The page is the shipped `index.html`**, not an instrumented copy. It already publishes
  `firstFrameMs` on `globalThis.__dogwoodReport` — `performance.now()` inside the first
  `withFrameNanos`, so it is measured from navigation start, by the page, in the page's own clock.
- **The throttling is Chrome's own**, applied through `Network.emulateNetworkConditions` over the
  DevTools Protocol, rather than a Python server pretending to be a slow link.
- **The bytes are the brotli'd ones.** Quality 11, matching `tools/web-weight/measure.sh`, so the
  bytes on the wire here are the bytes ADR-030's table reports. `run.sh` refuses to measure at all
  if the server is not actually serving `Content-Encoding: br`.
- **Every load is cold.** A fresh browser context per load — its own empty HTTP cache and its own
  empty compiled-WebAssembly cache — plus `Cache-Control: no-store` and `setCacheDisabled`. Reusing
  one target and reloading would measure a warm start after the first iteration and would look like
  a dramatic improvement rather than a mistake.
- **Percentiles are nearest-rank.** Interpolating between two of ten measurements invents a number
  that was never observed.

The presets are written as numbers rather than named, because the label has moved between Chrome
versions ("Fast 3G" became "Slow 4G") while the numbers did not:

| preset | download | upload | round trip |
|---|---|---|---|
| `none` | unthrottled | unthrottled | 0 ms |
| `4g` | 9 Mbit/s | 3.75 Mbit/s | 85 ms |
| `fast-3g` | 1.6 Mbit/s | 750 kbit/s | 562.5 ms |

## Results, 2026-09-03

Ten cold loads per preset, headless Chrome on Apple silicon, macOS. **3,103,293 bytes transferred**
on the render path in every load of every preset — identical across presets, which is itself the
check that the throttle changed only *how fast* the bytes arrived and not *which* bytes were asked
for.

| preset | p50 | p95 | min | max |
|---|---|---|---|---|
| `none` | **135 ms** | 215 ms | 122 ms | 215 ms |
| `4g` | **3,185 ms** | 3,206 ms | 3,170 ms | 3,206 ms |
| `fast-3g` | **17,415 ms** | 17,469 ms | 17,387 ms | 17,469 ms |

Raw samples in `results-2026-09-03.json`.

## What the numbers say

**Transfer dominates, and it is not close.** 3.03 MiB at 1.6 Mbit/s is 15.5 seconds of pure
transfer before anything else happens; the measurement is 17.4. At 9 Mbit/s the arithmetic gives
2.76 seconds and the measurement is 3.19. Unthrottled, the whole thing takes 135 ms. Everything the
host does *after* the bytes land — decompression, WebAssembly compilation, the first composition —
costs on the order of a hundred milliseconds, and the connection costs a hundred times that.

That means **page weight is the only lever that matters here**, which is the conclusion ADR-030
reached from the other direction and could not confirm on its own: a byte table cannot tell you
whether the bytes or the work dominate. Now it is measured.

**The spread is tiny, and that is a finding too.** p95 is within 0.4% of p50 on both throttled
presets. There is no long tail hiding a bad case; the number is the number, because the bottleneck
is a constant-rate pipe and not contention.

**Seventeen seconds is a product constraint, not a benchmark result.** It is written here in the
plainest terms available so that nobody has to rediscover it: on the connection a large share of the
world has, this profile shows nothing for seventeen seconds. Anything that changes that has to
remove bytes — the 2.5 MiB compressed WebAssembly module is where they are — and no amount of
host-side tuning will move it.

## Limits of this measurement

- **One machine, one browser, one build.** Headless Chrome on Apple silicon. A phone with less
  memory and a slower processor would pay more for WebAssembly compilation, and this run cannot say
  how much more, because compilation is the part the throttle does not touch.
- **A shaped pipe is not a real network.** `emulateNetworkConditions` gives a constant rate and a
  fixed round trip. A real cellular connection varies, drops, and re-establishes; its p95 would not
  be within 0.4% of its p50.
- **First frame is not first *useful* frame.** The page paints its tree, having already fetched
  everything; a product whose first frame depends on a further data request would add that request's
  latency on top of these numbers.
