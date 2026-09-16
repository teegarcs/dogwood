/*
 * Project Dogwood -- the payload's second entry point.
 *
 * A diagnostics screen, and the reason it exists is that it is the only way to *see* the host
 * service surface. Every fact on it is something the guest could not have worked out for itself:
 * which services this client offers, which revision of the surface it speaks, what time zone it is
 * in, which flags are set, and what it was launched with.
 *
 * It is also the second entry point, which is what makes the entry-point contract demonstrable
 * rather than described: one payload, two experiences, and the host chooses.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.LocalHostEnvironment
import dev.dogwood.compose.LocalLaunchParams
import dev.dogwood.compose.LocalSegmentVersions
import dev.dogwood.compose.Divider
import dev.dogwood.compose.SectionHeader
import dev.dogwood.compose.Text
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.Formats
import dev.dogwood.compose.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.dogwood.compose.Animations
import dev.dogwood.compose.Box
import dev.dogwood.compose.Column
import dev.dogwood.compose.Color
import dev.dogwood.compose.Keyboards
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Row
import dev.dogwood.compose.Spacer
import dev.dogwood.compose.padding
import dev.dogwood.compose.width
import kotlinx.serialization.json.JsonObject
import dev.dogwood.compose.animate
import dev.dogwood.compose.animateDp
import dev.dogwood.compose.oscillate
import dev.dogwood.compose.alpha
import dev.dogwood.compose.background
import dev.dogwood.compose.height
import dev.dogwood.compose.TextField
import dev.dogwood.compose.TextValue
import dev.dogwood.compose.ScrollArea
import dev.acme.guest.AcmeAction
import dev.acme.guest.AcmePanel
import dev.acme.guest.AcmePrice
import dev.acme.guest.AcmeTag
import dev.acme.guest.AcmeTone
import kotlinx.coroutines.launch
import dev.dogwood.compose.SnackbarArea
import dev.dogwood.compose.Arrangement
import dev.dogwood.compose.FontWeight
import dev.dogwood.compose.VerticalAlignment
import dev.dogwood.compose.border
import dev.dogwood.compose.clickable
import dev.dogwood.compose.contentDescription
import dev.dogwood.compose.size
import dev.dogwood.compose.SnackbarResult
import dev.dogwood.compose.rememberFocusRequester
import dev.dogwood.compose.rememberSnackbarHostState
import dev.dogwood.compose.rememberScrollState
import dev.dogwood.compose.rememberTextFieldState
import dev.dogwood.compose.services
import dev.dogwood.protocol.SERVICES_SEGMENT
import dev.dogwood.protocol.widthClass

@Composable
fun AboutScreen() {
  val host = services()
  val environment = LocalHostEnvironment.current
  val segments = LocalSegmentVersions.current
  val launch = LocalLaunchParams.current

  VerticalList(
    modifier = Modifier.fillMaxWidth(),
    spacingDp = 8,
    contentPaddingDp = 16,
  ) {
    SectionHeader(
      title = "Diagnostics",
      description = "Everything on this screen came from the host. None of it is knowable inside the sandbox.",
    )

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Services", description = null)
    Text(
      if (host.available.isEmpty()) {
        "this client offers no services at all"
      } else {
        host.available.sorted().joinToString(", ")
      },
    )
    Text("surface revision ${host.version} (${segments[SERVICES_SEGMENT] ?: "unreported"})")
    // The clock is the clearest case of a service that is not about capability. QuickJS has
    // Date.now(); it has no time zone, because the pinned build ships no Intl.
    Text("host clock ${host.nowEpochMillis() ?: "unavailable"}")
    Text("time zone ${host.clock?.timeZoneId() ?: "unavailable"}")

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Formatted by the host",
      description = "The sandbox has no Intl. Every line below crossed as a number.",
    )
    val now = host.nowEpochMillis() ?: 0L
    Text(Formats.currency(61200, "USD"))
    Text(Formats.currency(61200, "JPY"))
    Text(Formats.number(1234567.891))
    Text(Formats.percent(0.075, maximumFractionDigits = 1))
    Text(Formats.dateTime(now))
    Text(Formats.relativeTime(now - 3 * 86_400_000L, now))

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Text input",
      description = "The mask, the limit and the counter are all applied host-side, where the typing is.",
    )
    val card = rememberTextFieldState()
    // Two holders on one field, with different conflict rules. The text is version-vectored --
    // the host counts edits and discards a guest value stamped older than its own count -- and the
    // focus is a level-triggered target, the shape ADR-014 established for lists. Folding them
    // together would have put one inside the other; keeping them apart is why the field can be
    // focused by a guest that is behind on the text.
    val cardFocus = rememberFocusRequester()
    TextField(
      state = card,
      modifier = Modifier.fillMaxWidth(),
      label = TextValue("Card number"),
      mask = "#### #### #### ####",
      keyboard = Keyboards.NUMBER,
      showCounter = true,
      focus = cardFocus,
    )
    // The guest sees digits. It never sees the spaces, so changing the mask cannot change what
    // validation reads.
    Text("guest sees: \"${card.text}\"")
    // Both directions, on screen, so the holder can be verified on a device rather than argued
    // about. "Give it up" is a request in its own right and not the absence of one: a host that
    // read `requested = false` as "nothing was asked" would leave the keyboard up.
    Row(modifier = Modifier.fillMaxWidth()) {
      PrimaryButton(
        label = "Focus card number",
        onClick = { cardFocus.requestFocus() },
      )
      Spacer(modifier = Modifier.width(8))
      PrimaryButton(
        label = "Dismiss keyboard",
        onClick = { cardFocus.freeFocus() },
      )
    }

    Divider(modifier = Modifier.fillMaxWidth())

    var acmeTaps by remember { mutableStateOf(0) }
    SectionHeader(
      title = "A product's own components",
      description = "Segment 2. Acme wrote a surface and three implementations; the generator did " +
        "the rest, and this guest calls them exactly like Dogwood's own.",
    )
    // Nothing in this block is Dogwood's. `AcmePanel`, `AcmePrice` and `AcmeAction` come from
    // `dev.acme.guest`, generated from Acme's surface into Acme's module, and they record widgets
    // in a segment the engine has never heard of. If they render, a product can ship its own
    // design system -- which is the claim `specs/layer-5-host.md` calls item (c) of subsystem 9.
    AcmePanel(modifier = Modifier.fillMaxWidth(), inset = 16) {
      AcmePrice(amount = 129_900, currency = "USD", emphasis = "strong")
      AcmePrice(amount = 129_900, currency = "JPY")
      // An enumeration declared on Acme's surface, crossing as its name and coming back through
      // an event the same way. `remember`ed here, so a tap on the device changes the colour.
      var tone by remember { mutableStateOf(AcmeTone.Positive) }
      AcmeTag(label = "Acme tone ${tone.name}", tone = tone, onToneChange = { tone = it })
      Spacer(modifier = Modifier.height(8))
      AcmeAction(
        label = "Acme action",
        onClick = { acmeTaps += 1 },
      )
      Text("tapped $acmeTaps times")
      // Deliberately disabled, and `enabled` is marked `@Affordance` on Acme's surface — so this
      // control is withheld rather than drawn if a payload ever says something about it this
      // client cannot read. A product gets that guard by declaring it, not by remembering it.
      AcmeAction(label = "Acme unavailable", enabled = false, onClick = { })
    }

    Divider(modifier = Modifier.fillMaxWidth())

    var pillTaps by remember { mutableStateOf(0) }
    SectionHeader(
      title = "Composed in the payload",
      description = "Segment nobody's. A pill built from the primitive tier: no surface entry, " +
        "no tag, no release. Tap it.",
    )
    // The practical test of "no release for a new component" (ADR-069): a compositional component
    // written here, in the payload, from `clickable`, `border`, per-side `padding`, `spacedBy` and
    // `fontWeight`. The Android and iOS drills see it on a device; the render tests see the parts.
    Row(
      modifier = Modifier
        .border(1, Color(0xFF5C6BC0))
        .padding(horizontal = 10, vertical = 4)
        .contentDescription("payload pill, tapped $pillTaps times")
        .clickable(onClick = { pillTaps += 1 }),
      horizontalArrangement = Arrangement.spacedBy(6),
      verticalAlignment = VerticalAlignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(8).background(Color(0xFF5C6BC0)))
      Text("payload pill · $pillTaps", fontWeight = FontWeight.Medium)
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Scrolling container",
      description = "A position mirrored on a declared quantum. Drag it and watch the numbers move.",
    )
    // 40dp rather than the default, so a drag inside a 160dp window produces visible traffic. The
    // trade is on screen: a smaller quantum is a smoother read and more crossings, and there is no
    // value of it that yields per-frame state, because the host reports on a threshold.
    val terms = rememberScrollState(reportEveryDp = 40)
    Text(
      "offset ${terms.offsetDp}dp of ${terms.maxOffsetDp}dp" +
        (if (terms.isAtTop) " · at top" else "") +
        (if (terms.isAtBottom) " · at end" else "") +
        (if (terms.isScrollInProgress) " · scrolling" else ""),
    )
    ScrollArea(
      modifier = Modifier.fillMaxWidth().height(160),
      scroll = terms,
    ) {
      for (line in 1..24) {
        Text("Clause $line. Everything in here is composed, measured and kept.")
      }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
      PrimaryButton(label = "Top", onClick = { terms.animateScrollTo(0) })
      Spacer(modifier = Modifier.width(8))
      // The end is asked for as an intent, not a number: the guest cannot compute it, because the
      // maximum is host layout and the guest's copy is as stale as its last report.
      PrimaryButton(label = "End", onClick = { terms.animateScrollToEnd() })
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Animation",
      description = "One property crossed when the target changed. Every frame after that is host work.",
    )
    var expanded by remember { mutableStateOf(false) }
    var arrivals by remember { mutableStateOf(0) }
    PrimaryButton(
      label = if (expanded) "Collapse" else "Expand",
      modifier = Modifier.fillMaxWidth(),
      onClick = { expanded = !expanded },
    )
    // Deliberately disabled, and on screen. Conformance claim D7 asks whether a disabled control
    // is *announced* as disabled -- a screen reader user who is not told will try to operate it
    // and be met with nothing, which is the same failure ADR-031 withholds a widget to avoid. The
    // claim could not be made at all while no sample screen carried one.
    PrimaryButton(
      label = "Unavailable",
      modifier = Modifier.fillMaxWidth(),
      enabled = false,
      onClick = { arrivals += 1 },
    )
    // A skeleton row: an infinite oscillation, declared once. Every frame of it is host work.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(24)
        .alpha(oscillate(0.25f, 0.9f, Animations.tween(700, "linear")))
        .background(Color.token("canvasContrast")),
    )
    Text("skeleton: one property crossed, then nothing")

    Box(
      modifier = Modifier
        .fillMaxWidth()
        // Two animated arguments in one chain. Interrupt it mid-flight -- tap twice quickly --
        // and it retargets from wherever it is rather than restarting, because that is what
        // Compose's own `animateFloatAsState` does and this protocol declares targets rather than
        // starting animations.
        .height(animateDp(if (expanded) 160 else 24, Animations.spring(damping = "mediumBouncy")))
        .alpha(
          animate(
            if (expanded) 1f else 0.35f,
            Animations.tween(durationMs = 250),
            // A completion, not a retarget: an interrupted animation never reports one.
            onFinished = { arrivals += 1 },
          ),
        )
        // An animated colour. A theme flip mid-flight retargets rather than jumping.
        .background(Color.token(if (expanded) "primaryContainer" else "canvasContrast").animate()),
    )
    Text("arrived $arrivals times")

    Divider(modifier = Modifier.fillMaxWidth())

    var switched by remember { mutableStateOf(true) }
    var ticked by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(0) }
    SectionHeader(
      title = "Material 3, generated",
      description = "Segment 255. Nobody wrote these bindings: the generator read the library's " +
        "own sources and emitted both halves (plans/generator-v2.md). Tap them.",
    )
    // Fully qualified, because the primitive tier's `Text` is in scope and Material 3's is a
    // different component with a different tag. A payload picks by import; this screen uses both.
    dev.dogwood.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12)) {
        Row(verticalAlignment = VerticalAlignment.CenterVertically) {
          dev.dogwood.compose.material3.Switch(checked = switched, onCheckedChange = { switched = it })
          Text(if (switched) "switch on" else "switch off")
        }
        Row(verticalAlignment = VerticalAlignment.CenterVertically) {
          dev.dogwood.compose.material3.Checkbox(checked = ticked, onCheckedChange = { ticked = it })
          Text(if (ticked) "ticked" else "not ticked")
        }
        dev.dogwood.compose.material3.Button(onClick = { pressed += 1 }) {
          dev.dogwood.compose.material3.Text("material button · $pressed")
        }
        dev.dogwood.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
    }

    Divider(modifier = Modifier.fillMaxWidth())

    /*
     * Placed after the animation section on purpose.
     *
     * The Android accessibility drill reaches `Expand` and `Unavailable` by scrolling, and every
     * demonstration added above them pushes them further down. Two additions were enough to put
     * them out of reach, and the drill reported "the Expand button was never reachable" -- a true
     * sentence about the drill and a false one about the accessibility layer.
     *
     * The drill's scroll was made more robust at the same time; this is the other half, and it is
     * the cheaper one. A sample screen that a check depends on should not bury the thing it checks.
     */
    SectionHeader(
      title = "A holder that answers",
      description = "The guest asks for a snackbar and waits. What the user does decides what " +
        "happens next, which is the whole reason a snackbar is not a notification.",
    )
    val snackbars = rememberSnackbarHostState()
    val snackbarScope = rememberCoroutineScope()
    var undone by remember { mutableStateOf(0) }
    var expired by remember { mutableStateOf(0) }
    SnackbarArea(modifier = Modifier.fillMaxWidth(), snackbars = snackbars) {
      Column {
        Text("undone $undone · let it expire $expired")
        PrimaryButton(
          label = "Delete a row",
          modifier = Modifier.fillMaxWidth(),
          onClick = {
            snackbarScope.launch {
              // Suspends. The branch below is the point: a guest that ignored this answer would
              // have written a notification.
              when (snackbars.showSnackbar("Row deleted", actionLabel = "Undo")) {
                SnackbarResult.ACTION_PERFORMED -> undone += 1
                SnackbarResult.DISMISSED -> expired += 1
              }
            }
          },
        )
      }
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Guest logic over the environment",
      description = "No token, no contract. This screen read darkMode and picked a literal itself.",
    )
    // Ordinary Kotlin. The host receives the finished decision -- a plain colour -- and never
    // learns there was an `if`. Compare the box above it, whose colour is a design-system token.
    val guestChosen = if (isSystemInDarkTheme()) Color(0xFF7FD8BE) else Color(0xFF8A4FFF)
    Box(
      modifier = Modifier.fillMaxWidth().height(24).background(guestChosen),
    )
    Text(
      if (isSystemInDarkTheme()) "guest chose: seafoam, because dark" else "guest chose: violet, because light",
      color = guestChosen,
    )

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Feature flags", description = null)
    if (host.flags.isEmpty()) {
      Text("none set")
    } else {
      for ((name, value) in host.flags.entries.sortedBy { it.key }) {
        Text("$name = $value")
      }
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Launch", description = null)
    Text(launch.toString())

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Navigation",
      description = "Where this client will let a guest ask to go. The host interprets these; " +
        "this screen never learns what a route actually does.",
    )
    Text(
      if (host.routes.isEmpty()) {
        if (host.navigation == null) {
          "this client offers no navigation service"
        } else {
          "this client does not enumerate its routes, so any route may be tried"
        }
      } else {
        host.routes.sorted().joinToString(", ")
      },
    )
    // Deliberately bypasses `canNavigate`, which would refuse this locally. That is the point:
    // it simulates a payload built against a client with more destinations than this one, and
    // shows that the host reports the skew and stays put rather than failing. A real screen asks
    // first and does not draw the control -- see the button on the explore screen.
    PrimaryButton(
      label = "Ask for a route this client does not have",
      modifier = Modifier.fillMaxWidth().padding(4),
      onClick = {
        host.navigation?.navigate("experience/nowhere", JsonObject(emptyMap()))
      },
    )

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Dictionary", description = null)
    for ((segment, version) in segments.entries.sortedBy { it.key }) {
      Text("$segment v$version")
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Environment", description = null)
    Text("${environment.viewportWidthDp}×${environment.viewportHeightDp}dp, ${environment.widthClass}")
    Text("${if (environment.darkMode) "dark" else "light"}, ${environment.locale}, text ×${environment.fontScale}")
  }
}
