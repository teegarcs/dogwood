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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
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
  val experience = DogwoodWebExperience(environment, { line -> note(line) }, transcript)

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
        drive(experience, transcript)
      }
    }
  }
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
  if (node.tag.value == DogwoodDictionary.Row.value && node.boolean(1, false)) return node
  for (slot in 1..2) {
    for (child in node.children(slot)) {
      findClickableRow(child)?.let { return it }
    }
  }
  return null
}

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
