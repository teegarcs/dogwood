/*
 * Project Dogwood -- the web slice's page.
 *
 * The whole of the host side of the sample: run the correctness gate, read the environment, start
 * Compose Multiplatform, run the delivery check, and -- once a tree has arrived -- exercise the
 * two paths that a static screenshot could never show, the outbound event and the correlated
 * state snapshot.
 *
 * **Everything it observes is published as JavaScript Object Notation (JSON) on
 * `globalThis.__dogwoodReport`**, because the verification harness is a headless browser and a
 * report it cannot read is not a report. The page also writes a `#dogwood-report` element for a
 * human opening it in an ordinary browser.
 */
package dev.dogwood.slice.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.window.ComposeViewport
import dev.dogwood.protocol.EventTag
import dev.dogwood.web.BulkCopyGate
import dev.dogwood.web.DeliveryOutcome
import dev.dogwood.web.DogwoodWebExperience
import dev.dogwood.web.GateResult
import dev.dogwood.host.RenderTranscript
import dev.dogwood.web.WebDelivery
import dev.dogwood.host.DogwoodDictionary
import dev.dogwood.host.WidgetView
import dev.dogwood.host.boolean
import dev.dogwood.web.readHostEnvironment
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.dogwood.protocol.WebStartPayload

/** The report, built up as the page runs and republished on every change. */
private val log = mutableListOf<String>()
private val fields = LinkedHashMap<String, String>()

private fun note(line: String) {
  log += line
  publish()
}

private fun field(name: String, value: String) {
  fields[name] = value
  publish()
}

/** Hands the report to whatever is watching: the harness through a global, a human through a node. */
private fun publish() {
  val json = buildString {
    append('{')
    var first = true
    for ((name, value) in fields) {
      if (!first) append(',')
      first = false
      append('"').append(name).append("\":").append(quote(value))
    }
    if (!first) append(',')
    append("\"log\":[")
    append(log.joinToString(",") { quote(it) })
    append("]}")
  }
  setReport(json)
}

private fun quote(text: String): String = buildString {
  append('"')
  for (c in text) {
    when (c) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      // `String.format` is a Java-Virtual-Machine function and does not exist here, so the
      // control-character escape is spelled out.
      else -> if (c.code < 0x20) {
        append("\\u").append(c.code.toString(16).padStart(4, '0'))
      } else {
        append(c)
      }
    }
  }
  append('"')
}

/**
 * The harness's half of the report.
 *
 * Its own function because a `js(...)` body must be the whole of the function it appears in --
 * Kotlin/WebAssembly requires it, and the requirement is a reasonable one: the JavaScript is not
 * inlined into surrounding Kotlin, it becomes an imported function.
 */
private fun storeReport(json: String) {
  js("globalThis.__dogwoodReport = json")
}

private fun setReport(json: String) {
  storeReport(json)
  val element = document.getElementById("dogwood-report")
    ?: document.createElement("pre").also {
      it.setAttribute("id", "dogwood-report")
      it.setAttribute("style", "display:none")
      document.body?.appendChild(it)
    }
  element.textContent = json
}

/** Which sidecar to load, so the harness can point the same page at a manifest it must refuse. */
private fun manifestParameter(): String =
  js("new URLSearchParams(location.search).get('manifest') || 'dogwood-manifest.json'")

/**
 * Which experience to open.
 *
 * The *host's* choice now, rather than the guest reading its own Worker URL. That is the shape
 * every other client has -- `TabsActivity` names the entry and hands it launch parameters -- and it
 * is what lets one guest script serve four experiences from a page that decides between them.
 */
private fun entryParameter(): String =
  js("new URLSearchParams(location.search).get('entry') || 'about'")

/** This page's origin, which is the address the guest's own data service is served from. */
private fun origin(): String = js("location.origin")

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
  // Acme's design system, registered before anything renders — the same one call the Android host
  // makes in `Application.onCreate`. Without it a product's components arrive as inert
  // placeholders and are reported as skew, which is what the first run of the real Kotlin guest
  // showed: correct behaviour, and the wrong reason.
  dev.dogwood.host.DogwoodRegistry.register(dev.acme.design.AcmeDesignSystemBinding)

  // -----------------------------------------------------------------------------------------
  // 1. The correctness gate, before anything else.
  //
  // ADR-032 accepts the removal of `--gufa` on condition that the build keeps a gate which fails
  // if the output disagrees with a reference implementation. This is that gate running inside the
  // shipped binary, which is the only place it means anything: the defect is a miscompilation and
  // does not exist in an unoptimised build.
  // -----------------------------------------------------------------------------------------
  when (val gate = BulkCopyGate.check()) {
    is GateResult.Passed -> field("gate", "pass")
    is GateResult.Failed -> {
      field("gate", "FAIL")
      note(gate.detail)
      // Deliberately fatal. A host whose decoder is miscompiled renders an empty screen and says
      // nothing, which is precisely the failure the gate exists to make loud.
      return
    }
  }

  val environment = readHostEnvironment()
  field("environment", environment.toString())

  val transcript = RenderTranscript()
  /*
   * What this page tells the guest about itself.
   *
   * The same four things the Android and iOS hosts pass through `DogwoodServiceHost` and
   * `DogwoodShell.activate`, and until now the web passed none of them -- which is why the sample's
   * own Diagnostics screen read `surface revision 0 (unreported)` and `host clock unavailable`
   * while claiming to run the same screens as the mobile payload. It did run them; it ran them
   * blind.
   */
  val services = WebStartPayload(
    entryPoint = entryParameter(),
    // The same parameters `TabsActivity` and the iOS host pass. `apiBaseUrl` is the page's own
    // origin, because only the host knows which name reaches the machine serving the payload --
    // `10.0.2.2` on an Android emulator, `localhost` on a simulator, and this on the web.
    launchParams = buildJsonObject {
      put("city", JsonPrimitive("Tokyo"))
      put("country", JsonPrimitive("Japan"))
      put("apiBaseUrl", JsonPrimitive(origin()))
    },
    featureFlags = mapOf("explore.showWasPrice" to "true"),
    // Deliberately empty, and empty means "this host does not enumerate" rather than "handles
    // nothing" -- `DogwoodNavigation.routes` says so. The sample's own route button is therefore
    // expected to be declined and recorded as skew, which is the path worth exercising.
    routes = emptySet(),
    // The one line that answers "surface revision 0 (unreported)". A guest branches on this to
    // decide what it may use, so a client that reports nothing is a client every guest assumes is
    // empty.
    segmentVersions = DogwoodDictionary.segmentVersions,
  )
  val experience = DogwoodWebExperience(
    environment,
    { line -> note(line) },
    transcript,
    services = services,
    onAnalytics = { event -> note("analytics: ${event.name} ${event.properties}") },
    onNavigate = { request ->
      note("navigation refused: no route '${request.route}'")
      false
    },
  )

  ComposeViewport(document.body!!) {
    experience.Content()
    FirstFrame()
  }

  val scope = CoroutineScope(Dispatchers.Main)
  scope.launch {
    val delivery = WebDelivery(DogwoodDictionary.segmentVersions) { refusal ->
      field("refused", refusal::class.simpleName ?: "refusal")
      note("delivery refused: ${refusal.message}")
    }
    val manifest = manifestParameter()
    field("manifest", manifest)
    when (val outcome = delivery.start(manifest, experience)) {
      is DeliveryOutcome.Refused -> {
        field("workerCreated", "false")
        note("no Worker was created")
      }

      is DeliveryOutcome.Started -> {
        field("workerCreated", "true")
        experience.attach(outcome.bridge)
        // A code update, on demand, because on this architecture it is the *normal* case and the
        // web profile had never once been made to do it. The harness asks by setting a global; the
        // page does the whole thing a publish would do -- fetch the manifest again, create a new
        // Worker, and hand the running guest's state to its successor.
        scope.launch { serveCodeUpdates(delivery, manifest, experience) }
        // Polled alongside the drive rather than read once after it, and the difference is not
        // cosmetic. `SkewReport` is plain sets written *during* composition -- `Skew.kt` explains
        // why it cannot be snapshot state -- so a single read after the batches have applied sees
        // only what the tree recorded while applying them, and none of what the bindings recorded
        // while drawing them. Reading it once reported the unknown widget tag and missed the
        // withheld one, which is precisely the half that matters: the drill's `A4-reported` went
        // red on a client that had withheld the control correctly.
        scope.launch { pollSkew(experience) }
        drive(experience, transcript)
      }
    }
  }
}

/**
 * Runs a code update whenever the harness asks for one.
 *
 * Polling a global rather than exporting a function, because a Kotlin/WebAssembly function is not a
 * JavaScript value and wrapping one to be called from a headless browser would be more interop than
 * the thing it is testing.
 */
private suspend fun serveCodeUpdates(
  delivery: WebDelivery,
  manifest: String,
  experience: DogwoodWebExperience,
) {
  while (true) {
    delay(200)
    if (!codeUpdateRequested()) continue
    clearCodeUpdateRequest()
    when (val outcome = delivery.start(manifest, experience)) {
      is DeliveryOutcome.Started -> {
        experience.update(outcome.bridge)
        updates += 1
        field("codeUpdates", updates.toString())
      }

      is DeliveryOutcome.Refused -> note("a code update was refused: ${outcome.refusal.message}")
    }
  }
}

private var updates = 0

private fun codeUpdateRequested(): Boolean = js("globalThis.__dogwoodCodeUpdate === true")

private fun clearCodeUpdateRequest() {
  js("globalThis.__dogwoodCodeUpdate = false")
}

/**
 * Exercises the paths a screenshot cannot show.
 *
 * A rendered tree proves the guest-to-host direction. It does not prove that an event reaches the
 * guest, that the guest's answer comes back as a new batch, or that a correlated request gets its
 * own reply rather than somebody else's. So this waits for the first tree, taps the bound row the
 * way a finger would, waits for the guest's answer, and then asks for a state snapshot.
 *
 * **The tap is dispatched through the same [DogwoodWebExperience.dispatchEvent] the `clickable`
 * binding calls**, not through a shortcut around it. What it does *not* exercise is Compose's own
 * hit testing -- synthesising a pointer event onto a WebGL canvas from a headless browser tests the
 * browser more than it tests Dogwood -- so the claim this makes is "the event path works", not
 * "the click works".
 */
private suspend fun drive(experience: DogwoodWebExperience, transcript: RenderTranscript) {
  if (!awaitBatches(experience, 1)) {
    note("the guest never sent a batch")
    return
  }
  field("treeAfterFirstBatch", experience.tree.describe())
  field("transcript", transcript.dump())
  field("renderedNodes", transcript.count.toString())

  val row = findClickableRow(experience.tree.root)
  if (row == null) {
    note("no clickable row in the tree; the event path was not exercised")
  } else {
    experience.dispatchEvent(row, EventTag(1))
    if (awaitBatches(experience, 2)) {
      field("treeAfterEvent", experience.tree.describe())
    } else {
      note("the guest did not answer the event with a new batch")
    }
  }

  var snapshot: String? = null
  experience.snapshotState { snapshot = it.values.toString() }
  var waited = 0
  while (snapshot == null && waited < 2000) {
    delay(25)
    waited += 25
  }
  field("snapshot", snapshot ?: "(never answered)")
  field("appliedBatches", experience.appliedBatches.toString())
  // Re-read at the end, because the measured sizes are recorded during a layout pass that had not
  // happened yet when the transcript was first published.
  field("transcript", transcript.dump())
  field("renderedNodes", transcript.count.toString())
  field("done", "true")
}

/**
 * Republishes the skew report while the page runs.
 *
 * This is what a host wiring `SkewReport` to telemetry actually does -- `SkewDrain` in the
 * engine is the same shape -- and it is the only way to see an entry a binding recorded during
 * composition, because nothing invalidates when one lands. Bounded rather than endless: the page
 * is a harness, and a coroutine that never finishes would keep it from ever looking idle.
 */
private suspend fun pollSkew(experience: DogwoodWebExperience) {
  repeat(60) {
    field("skew", experience.tree.skew.toString())
    delay(250)
  }
}

private suspend fun awaitBatches(experience: DogwoodWebExperience, count: Int): Boolean {
  var waited = 0
  while (experience.appliedBatches < count && waited < 5000) {
    delay(25)
    waited += 25
  }
  return experience.appliedBatches >= count
}

/** The first `Row` the guest marked as carrying a handler, which is where a tap would land. */
private fun findClickableRow(node: WidgetView): WidgetView? {
  // Two shapes, because there are two guests and the harness must not be written for one of them.
  //
  // The hand-written JavaScript guest builds a clickable `Row`. The real Kotlin guest composes the
  // sample's About screen, which has no such row and a great many buttons -- so this looked for a
  // shape only one guest produced and reported "the event path was not exercised", which is true
  // and reads like an absence of buttons rather than an absence of *rows*.
  if (node.tag.value == DogwoodDictionary.Row.value && node.boolean(1, false)) return node
  if (node.tag.value == PRIMARY_BUTTON && node.boolean(2, true)) return node
  for (slot in 1..2) {
    for (child in node.children(slot)) {
      findClickableRow(child)?.let { return it }
    }
  }
  return null
}

/**
 * `PrimaryButton`, whose `onClick` is event tag 1 exactly as a clickable `Row`'s is.
 *
 * Named by tag rather than imported, because this page links `dogwood-host`'s core and the
 * design-system dictionary is generated -- and a page that hard-codes a *number* would be the kind
 * of hand-maintained copy the generator exists to remove. This is a test harness reaching for one
 * widget, and it says which.
 */
private val PRIMARY_BUTTON = dev.dogwood.protocol.widgetTag(1, 1).value

/**
 * The time to the first frame Compose produced, which is the figure ADR-030 says actually decides
 * adoption and which nothing in the page-weight harness could measure.
 *
 * Taken from inside the composition, on the first frame, so it counts everything before it:
 * fetching the WebAssembly, compiling it, Skiko's startup, and the first composition.
 */
@Composable
private fun FirstFrame() {
  LaunchedEffect(Unit) {
    withFrameNanos { }
    field("firstFrameMs", window.performance.now().toInt().toString())
    val element = document.createElement("div")
    element.setAttribute("id", "dogwood-first-frame")
    element.textContent = window.performance.now().toInt().toString()
    document.body?.appendChild(element)
  }
}
