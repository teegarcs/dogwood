/*
 * Project Dogwood -- the harness's eyes.
 *
 * A separate file rather than an inline script because the page it is added to keeps the sample's
 * own Content Security Policy, and that policy has no `'unsafe-inline'` for scripts. Testing a
 * page with its protections removed tests a page nobody ships.
 *
 * It waits for the host to publish a terminal report -- `done` on the success path, `refused` on
 * the refusal path -- and posts it back to the server, which is what ends the run. A timeout posts
 * whatever has accumulated, because a run that hangs should produce a partial report rather than
 * no information at all.
 */
(function () {
  var elapsed = 0;
  var timer = setInterval(function () {
    elapsed += 250;
    var report = globalThis.__dogwoodReport;
    var parsed = null;
    try { parsed = report ? JSON.parse(report) : null; } catch (e) { parsed = null; }
    var finished = parsed && (parsed.done === 'true' || parsed.refused || parsed.gate === 'FAIL');
    if (finished || elapsed >= 30000) {
      clearInterval(timer);
      var payload = parsed || { log: ['the host published no report at all'] };
      payload.harness = {
        timedOut: !finished,
        elapsedMs: elapsed,
        // Compose Multiplatform's `ComposeViewport` puts its canvas inside a shadow root, so a
        // plain `querySelectorAll` finds nothing and reports a blank page that is not blank.
        canvas: (function () {
          function find(root) {
            var direct = root.querySelector && root.querySelector('canvas');
            if (direct) return direct;
            var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
            for (var i = 0; i < all.length; i++) {
              if (all[i].shadowRoot) {
                var inner = find(all[i].shadowRoot);
                if (inner) return inner;
              }
            }
            return null;
          }
          var c = find(document);
          return c ? c.width + 'x' + c.height : 'none';
        })(),
      };
      fetch('/report', { method: 'POST', body: JSON.stringify(payload) });
    }
  }, 250);
})();
