/*
 * Project Dogwood -- a guest, in a Web Worker, in about a hundred lines.
 *
 * Layer 5 ADR-032 decides that the web guest is "ordinary JavaScript in a Web Worker" and that it
 * speaks "the identical positional JavaScript Object Notation (JSON) batch". This is the smallest
 * thing that is honestly both. It is NOT what a real guest is: a real guest is Kotlin compiled to
 * JavaScript, running a Compose composition, with `dogwood-compose`'s applier producing the batch.
 * This one writes the batch by hand, which is exactly the point -- if a hand-written guest speaks
 * the protocol, the protocol is the interface rather than an artefact of the Kotlin on both sides.
 *
 * WHAT THE WORKER GIVES, AND WHAT IT DOES NOT
 *
 * Inside a Worker there is no `document`, no `window` and no access to the host page or its
 * storage, which is the isolation half of ADR-032's threading decision -- a property of running
 * here rather than an addition to it. What a Worker does NOT give is origin isolation: this script
 * shares the page's origin, so it can reach same-origin endpoints and carries the page's
 * credentials on requests a Content Security Policy permits. The policy is what makes the network
 * allow-list enforcement rather than advice, and it is set by the page, not by this file.
 *
 * THE ENVELOPE
 *
 * Mirrored by hand from `dogwood-web/WorkerProtocol.kt`. The host checks the revision this file
 * announces before exchanging anything else, so a mirror that has drifted is refused rather than
 * guessed at.
 */

const REVISION = 1;

/*
 * A liveness beacon, read by the verification harness and by nothing else.
 *
 * It exists to prove a negative that is otherwise unprovable from outside: that when the sidecar
 * manifest names a dictionary version the client does not implement, this file **never runs**. A
 * harness can see that no tree appeared, but "no tree" is also what a broken guest looks like. A
 * request that the server either did or did not receive is unambiguous.
 */
fetch('guest-ran').catch(() => {});

// ---------------------------------------------------------------------------------------------
// The composition, such as it is.
// ---------------------------------------------------------------------------------------------

/*
 * Widget tags are `(segment << 24) | local`, and this guest uses segment 0 -- the layout tier --
 * so a tag is its local number. These five are the whole of what the web host binds.
 */
const TEXT = 1, COLUMN = 2, ROW = 3, BOX = 4;

/* Change-kind discriminators, from `dev.dogwood.protocol.ChangeKind`. Permanent; never renumbered. */
const CREATE = 0, PROPERTY = 1, MODIFIER = 2, CHILD_ADD = 3;

/* Modifier tags, from `dev.dogwood.protocol.ModifierTags`. */
const M_PADDING = 1, M_SIZE = 4, M_BACKGROUND = 10;

/* Deferred-expression factories, from `dev.dogwood.protocol.ExpressionFactories`. */
const COLOR_ARGB = 3;

/* The one content slot the layout tier's containers declare, and the root's. */
const CONTENT = 1;

/* Node identifiers. Monotonic within a composition and never reused, exactly as on mobile. */
const COLUMN_ID = 1, TITLE_ID = 2, BODY_ID = 3, ROW_ID = 4, COUNTER_ID = 5, SWATCH_ID = 6;

let sequence = 0;
let taps = 0;
let environment = null;

/*
 * The frame times the host has delivered, and the correlation identifiers they came back on.
 *
 * Kept only so the harness can assert that the correlated guest-to-host direction completed. A real
 * guest resumes a `withFrameNanos` continuation here and keeps nothing.
 */
let framesReceived = 0;
let lastFrameCorrelation = 0;

/**
 * The first batch: everything, because nothing exists yet.
 *
 * Note the shape of each tuple against the grammar in `PositionalCodec.kt`:
 *   create   = [0, id, widgetTag]
 *   property = [1, id, propertyTag, value]
 *   modifier = [2, id, [[tag, value], ...]]
 *   add      = [3, parentId, slot, childId, index]
 *
 * Property tags are parameter-declaration order and widget-scoped: on `Text`, 1 is the string and
 * 3 is the style name; on `Row`, 1 is "this node carries handler 1", which is how a host that
 * cannot see guest closures learns to make the row clickable at all.
 */
function initialBatch() {
  sequence += 1;
  return JSON.stringify([sequence, [
    [CREATE, COLUMN_ID, COLUMN],
    [MODIFIER, COLUMN_ID, [[M_PADDING, 24]]],
    [CHILD_ADD, 0, CONTENT, COLUMN_ID, 0],

    [CREATE, TITLE_ID, TEXT],
    [PROPERTY, TITLE_ID, 1, 'Dogwood on the web'],
    [PROPERTY, TITLE_ID, 3, 'title'],
    [CHILD_ADD, COLUMN_ID, CONTENT, TITLE_ID, 0],

    [CREATE, BODY_ID, TEXT],
    [PROPERTY, BODY_ID, 1, 'This tree was composed in a Web Worker and applied by a ' +
      'Kotlin/WebAssembly host.'],
    [CHILD_ADD, COLUMN_ID, CONTENT, BODY_ID, 1],

    [CREATE, ROW_ID, ROW],
    [PROPERTY, ROW_ID, 1, true],
    [CHILD_ADD, COLUMN_ID, CONTENT, ROW_ID, 2],

    [CREATE, COUNTER_ID, TEXT],
    [PROPERTY, COUNTER_ID, 1, 'taps: ' + taps],
    [CHILD_ADD, ROW_ID, CONTENT, COUNTER_ID, 0],

    // A painted thing, so that "the page is not blank" is checkable in pixels as well as in text.
    // 4278222976 is 0xFF008080: opaque teal, and nothing like the white behind it.
    [CREATE, SWATCH_ID, BOX],
    [MODIFIER, SWATCH_ID, [[M_SIZE, 48], [M_BACKGROUND, [COLOR_ARGB, 4278222976]]]],
    [CHILD_ADD, COLUMN_ID, CONTENT, SWATCH_ID, 3],
  ]]);
}

/**
 * The batch after a tap: one property.
 *
 * This is what a diff protocol is for. The mobile host would send exactly this, and the fact that
 * it is one change rather than a re-description of the tree is why the sequence number, not the
 * content, is what orders the stream.
 */
function counterBatch() {
  sequence += 1;
  return JSON.stringify([sequence, [
    [PROPERTY, COUNTER_ID, 1, 'taps: ' + taps],
  ]]);
}

// ---------------------------------------------------------------------------------------------
// The envelope.
// ---------------------------------------------------------------------------------------------

function post(t, c, p) {
  self.postMessage({ t: t, c: c, p: p });
}

/*
 * Correlation identifiers this guest allocates.
 *
 * Even numbers only; the host allocates odd ones. Two counters over one number space would collide
 * the moment both sides had a request outstanding, and the collision would not fail -- it would
 * deliver one side's answer to the other side's caller.
 */
let nextCorrelation = 2;

self.onmessage = function (event) {
  const message = event.data;
  if (!message || typeof message.t !== 'string') return;
  switch (message.t) {
    case 'configuration': {
      environment = JSON.parse(message.p);
      // The first composition happens here and not before: a guest cannot lay out without knowing
      // its viewport, which is the one fact it can never work out for itself.
      post('changes', 0, initialBatch());
      // Ask for a frame, purely to exercise the correlated guest-to-host direction. A real guest
      // asks when an animation is running.
      post('requestFrame', nextCorrelation, '');
      nextCorrelation += 2;
      break;
    }

    case 'event': {
      const decoded = JSON.parse(message.p);
      // `q` is the sequence of the last batch the host had APPLIED when the interaction occurred,
      // which is what lets a guest drop an event aimed at a tree it has already replaced. The
      // window is wider here than on mobile because the event has a Worker hop to make.
      if (decoded.q < sequence - 1) break;
      if (decoded.i === ROW_ID && decoded.e === 1) {
        taps += 1;
        post('changes', 0, counterBatch());
      }
      break;
    }

    case 'frame': {
      // Nothing to animate. A real guest resumes its `withFrameNanos` continuation with `message.p`,
      // which is the frame time in nanoseconds as a decimal string. Recorded rather than used, so
      // that the harness can assert the answer came back on the identifier the request went out on
      // -- an uncorrelated bridge would still deliver *a* frame and look identical.
      framesReceived += 1;
      lastFrameCorrelation = message.c;
      break;
    }

    case 'snapshotState': {
      // The values are whatever this guest's `rememberSaveable` call sites produced. They cross
      // the boundary, so they must be serializable -- a real constraint on what a guest may
      // declare saveable, not an implementation detail.
      post('result', message.c, JSON.stringify({
        values: {
          taps: [taps],
          framesReceived: [framesReceived],
          lastFrameCorrelation: [lastFrameCorrelation],
        },
      }));
      break;
    }

    default:
      post('error', message.c || 0, "guest does not implement message kind '" + message.t + "'");
  }
};

// Announced last, so that nothing can arrive before the handler above is installed.
post('ready', 0, String(REVISION));
