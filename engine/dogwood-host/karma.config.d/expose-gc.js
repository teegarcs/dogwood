// Project Dogwood -- give the browser a forced garbage collection, for the leak tests only.
//
// A leak detector's negative control -- "an object that should be collected is not reported" --
// cannot be written without making collection happen on demand. The browser offers no standard
// way; Chrome does behind `--js-flags=--expose-gc`, which publishes `globalThis.gc()`.
//
// This affects the test browser and nothing that ships. `BrowserLeakWatcher` never calls `gc()`:
// it reports what is still reachable after a threshold, exactly as the other hosts' detectors do.
config.set({
  browsers: ["ChromeHeadlessExposeGc"],
  customLaunchers: {
    ChromeHeadlessExposeGc: {
      base: "ChromeHeadless",
      flags: ["--js-flags=--expose-gc"],
    },
  },
});
