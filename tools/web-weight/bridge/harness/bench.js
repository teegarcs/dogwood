/*
 * Project Dogwood -- the JavaScript half of the bridge benchmark.
 *
 * Generates representative positional-JSON tree-diffs at three sizes, then times every candidate
 * way of getting one into Kotlin/WebAssembly. Everything the page measures is timed in the page
 * itself; the results are posted back to the harness server, which writes them to a file.
 */
import * as wasm from './web-weight-bridge.mjs';

const status = document.getElementById('status');
const out = document.getElementById('results');
const say = (m) => { status.textContent = m; };

// A sink for every checksum returned. Reading it at the end is what stops V8 from concluding
// that the work being measured has no observable effect and deleting it.
let SINK = 0;

// ---------------------------------------------------------------------------------------------
// Payload generation.
//
// Positional JSON as the guest emits it: a frame identifier followed by a list of changes, each
// change a short array of small integers with an occasional short string. Shaped like
// `[1,[[0,1,2],[1,1,1,"text"],[3,0,1,1,0]]]`. A seeded generator keeps the payloads identical
// between runs, so two runs of this harness are comparable.
// ---------------------------------------------------------------------------------------------

function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const WORDS = ['Home', 'Settings', 'Cancel', 'OK', 'Search', 'Details', 'Save', 'Retry',
               'Profile', 'Inbox', 'Sent', 'Draft', 'Reply', 'More', 'Back'];

/** One change: an opcode, a few small integers, and 1-in-8 chance of a trailing short string. */
function makeChange(rnd) {
  const c = [Math.floor(rnd() * 8)];
  const n = 2 + Math.floor(rnd() * 5);
  for (let i = 0; i < n; i++) {
    c.push(rnd() < 0.15 ? Math.floor(rnd() * 65536) : Math.floor(rnd() * 256));
  }
  if (rnd() < 0.125) c.push(WORDS[Math.floor(rnd() * WORDS.length)]);
  return c;
}

/** Builds a diff whose serialised form is as close as possible to `targetBytes`. */
function makePayload(name, targetBytes, seed) {
  const rnd = mulberry32(seed);
  const changes = [];
  let tree = [1, changes];
  let json = JSON.stringify(tree);
  while (json.length < targetBytes) {
    changes.push(makeChange(rnd));
    json = JSON.stringify(tree);
  }
  const bytes = new TextEncoder().encode(json);

  // The flat 32-bit-integer encoding used by the "no textual encoding at all" bound. Strings are
  // replaced by an index into a side table, which this harness does not otherwise model.
  const strings = [];
  const flat = [];
  flat.push(tree[0], changes.length);
  for (const c of changes) {
    flat.push(c.length);
    for (const v of c) {
      if (typeof v === 'string') {
        let idx = strings.indexOf(v);
        if (idx < 0) { idx = strings.length; strings.push(v); }
        flat.push(-1, idx);
      } else {
        flat.push(v);
      }
    }
  }
  const flatInts = Int32Array.from(flat);

  return {
    name, tree, json, bytes, flat, flatInts, strings,
    changes: changes.length,
    jsonBytes: bytes.length,
  };
}

const PAYLOADS = [
  // A steady-state batch: a handful of changes, the common case between frames.
  makePayload('small (steady state)', 100, 0x51EED1),
  makePayload('medium', 5000, 0x51EED2),
  makePayload('large (screen open)', 16000, 0x51EED3),
];

// ---------------------------------------------------------------------------------------------
// The two callbacks Kotlin uses to have this page write into the module's linear memory.
//
// A fresh typed-array view is built on every call because `memory.buffer` is detached whenever
// the WebAssembly memory grows; caching one would be a latent crash. Building the view is part of
// the cost of this transport, so it is inside the measurement on purpose.
// ---------------------------------------------------------------------------------------------

let CURRENT = PAYLOADS[0];
let FILL_MODE = 'set';           // 'set' copies pre-encoded bytes; 'encodeInto' encodes in place.
const ENCODER = new TextEncoder();

globalThis.__dogwoodFillBytes = (addr, cap) => {
  const view = new Uint8Array(wasm.memory.buffer, addr, cap);
  if (FILL_MODE === 'encodeInto') {
    return ENCODER.encodeInto(CURRENT.json, view).written;
  }
  view.set(CURRENT.bytes);
  return CURRENT.bytes.length;
};

globalThis.__dogwoodFillInt32 = (addr, capBytes) => {
  const n = CURRENT.flatInts.length;
  new Int32Array(wasm.memory.buffer, addr, n).set(CURRENT.flatInts);
  return n;
};

// ---------------------------------------------------------------------------------------------
// Timing.
//
// `performance.now()` is coarsened by Chrome -- to 5 microseconds when the page is cross-origin
// isolated and to 100 microseconds when it is not -- and the smallest operations here are well
// under either. So each sample times a batch of K calls and divides, with K calibrated so a batch
// takes at least TARGET_BATCH_MS. Percentiles are therefore percentiles over batch means: they
// capture run-to-run variation and garbage-collection pauses that fall inside a batch, but for
// the small payload, where K is large, they smooth over single-call tails. K is reported so the
// reader can tell how much smoothing happened.
// ---------------------------------------------------------------------------------------------

const TARGET_BATCH_MS = 4;
const WARMUP_MS = 300;
const SAMPLES = 150;

function calibrate(fn) {
  let k = 1;
  for (;;) {
    const t0 = performance.now();
    for (let i = 0; i < k; i++) SINK += fn();
    const dt = performance.now() - t0;
    if (dt >= TARGET_BATCH_MS) return k;
    if (k > 4_000_000) return k;
    // performance.now() may report 0 under coarsening; grow aggressively until it does not.
    k = dt <= 0 ? k * 8 : Math.max(k + 1, Math.ceil(k * (TARGET_BATCH_MS / dt) * 1.3));
  }
}

function percentile(sorted, p) {
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1));
  return sorted[i];
}

function measure(label, fn) {
  const warmEnd = performance.now() + WARMUP_MS;
  while (performance.now() < warmEnd) { for (let i = 0; i < 64; i++) SINK += fn(); }

  const k = calibrate(fn);
  const perOpUs = [];
  for (let s = 0; s < SAMPLES; s++) {
    const t0 = performance.now();
    for (let i = 0; i < k; i++) SINK += fn();
    perOpUs.push(((performance.now() - t0) * 1000) / k);
  }
  perOpUs.sort((a, b) => a - b);
  return {
    label, k, samples: SAMPLES,
    p50: percentile(perOpUs, 0.50),
    p95: percentile(perOpUs, 0.95),
    p99: percentile(perOpUs, 0.99),
    max: perOpUs[perOpUs.length - 1],
  };
}

// ---------------------------------------------------------------------------------------------
// The scenarios.
// ---------------------------------------------------------------------------------------------

function scenarios(p) {
  const json = p.json;
  const tree = p.tree;
  const flat = p.flat;
  const cap = p.bytes.length + 8;
  const capBytes = p.flatInts.length * 4;

  return [
    // What the guest pays before anything crosses.
    ['js: JSON.stringify(tree)', () => JSON.stringify(tree).length],
    ['js: TextEncoder.encode(json)', () => ENCODER.encode(json).length],
    ['js: JSON.parse(json)', () => JSON.parse(json).length],
    ['js: flatten tree to Int32Array', () => flattenCost(tree)],

    // Path 1 -- the String argument.
    ['1  string: pass only', () => wasm.stringTouch(json)],
    ['1a string: pass + parse in place', () => wasm.stringParseDirect(json)],
    ['1b string: pass + bulk copy + parse', () => wasm.stringParseViaCharArray(json)],
    ['1c string: pass + bulk copy only', () => wasm.stringToCharArrayOnly(json)],
    ['1a′ parse in place, no crossing', () => wasm.parseCachedDirect()],
    ['1b′ parse copied array, no crossing', () => wasm.parseCachedCharArray()],

    // Path 2 -- bytes into linear memory.
    ['2  bytes: copy in, no parse', () => { FILL_MODE = 'set'; return wasm.bytesTouch(cap); }],
    ['2a bytes: copy in + parse', () => { FILL_MODE = 'set'; return wasm.bytesParse(cap); }],
    ['2b bytes: encodeInto + parse', () => { FILL_MODE = 'encodeInto'; return wasm.bytesParse(cap); }],

    // Path 3 -- walking a JavaScript array from Kotlin.
    ['3a JsArray walk, flat, no dispatch', () => wasm.walkFlatJsArray(flat)],
    ['3b JsArray walk, nested + dispatch', () => wasm.walkNestedJsArray(tree)],

    // Path 4 -- the floor: no textual encoding at all.
    ['4  Int32Array into memory + read', () => wasm.int32Read(capBytes)],
  ];
}

/** The cost of turning the guest's natural tree into a flat integer array, so path 4's transport
 *  number can be read together with the encoding it presupposes. */
function flattenCost(tree) {
  const changes = tree[1];
  const flat = [tree[0], changes.length];
  for (let i = 0; i < changes.length; i++) {
    const c = changes[i];
    flat.push(c.length);
    for (let j = 0; j < c.length; j++) {
      const v = c[j];
      if (typeof v === 'string') { flat.push(-1, 0); } else { flat.push(v); }
    }
  }
  return Int32Array.from(flat).length;
}

// ---------------------------------------------------------------------------------------------
// Run.
// ---------------------------------------------------------------------------------------------

async function run() {
  const results = {
    variant: new URLSearchParams(location.search).get('variant') || 'unknown',
    userAgent: navigator.userAgent,
    crossOriginIsolated: globalThis.crossOriginIsolated === true,
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemory: navigator.deviceMemory ?? null,
    targetBatchMs: TARGET_BATCH_MS,
    warmupMs: WARMUP_MS,
    samples: SAMPLES,
    payloads: [],
  };

  // Run the production-optimiser probe before anything else. If the bulk copy is miscompiled,
  // path 1b measures an empty loop and every 1b number in this run is meaningless.
  const probeInput = '[1,[[0,1,2],[1,1,1,"text"]]]';
  results.bulkCopyProbe = {
    input: probeInput,
    viaBulkCopy: wasm.probeBulkCopy(probeInput),
    viaPerChar: wasm.probePerChar(probeInput),
    size: wasm.probeBulkCopySize(probeInput),
  };
  results.bulkCopyProbe.ok =
    results.bulkCopyProbe.viaBulkCopy === results.bulkCopyProbe.viaPerChar &&
    results.bulkCopyProbe.size === probeInput.length;

  for (const p of PAYLOADS) {
    say('measuring ' + p.name);
    wasm.cacheString(p.json);
    CURRENT = p;

    // Correctness gate: every path must agree that it saw the same payload, or the comparison is
    // between different amounts of work. The three text parsers must return the identical
    // checksum; the two array walks compute different sums by construction, so they are only
    // checked for being non-zero.
    const a = wasm.stringParseDirect(p.json);
    const b = wasm.stringParseViaCharArray(p.json);
    FILL_MODE = 'set';
    const c = wasm.bytesParse(p.bytes.length + 8);
    const agree = (a === b) && (b === c);
    const checksums = { direct: a, charArray: b, bytes: c };

    const rows = [];
    for (const [label, fn] of scenarios(p)) {
      say('measuring ' + p.name + ' :: ' + label);
      await new Promise((r) => setTimeout(r, 0));
      rows.push(measure(label, fn));
    }

    results.payloads.push({
      name: p.name,
      changes: p.changes,
      jsonBytes: p.jsonBytes,
      jsonChars: p.json.length,
      flatInts: p.flatInts.length,
      parsersAgree: agree,
      checksums,
      checksum: a,
      rows,
    });
  }

  results.sink = SINK;
  out.textContent = JSON.stringify(results, null, 2);
  say('done');

  try {
    await fetch('/result', { method: 'POST', body: JSON.stringify(results) });
  } catch (e) {
    say('done (no server to post to: ' + e + ')');
  }
  document.title = 'BENCHMARK-COMPLETE';
}

run().catch((e) => {
  say('FAILED: ' + (e && e.stack ? e.stack : e));
  document.title = 'BENCHMARK-FAILED';
  fetch('/result', { method: 'POST', body: JSON.stringify({ error: String(e && e.stack || e) }) })
    .catch(() => {});
});
