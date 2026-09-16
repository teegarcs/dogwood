/*
 * Project Dogwood -- the generated Material 3 tier, on a screen, on every client.
 *
 * Nobody wrote the components this screen calls. The generator read `material3`'s own sources and
 * emitted a guest stub and a host binding for each (ADR-072), and until this screen existed
 * seventy-four of them had compiled and never been composed. A binding that has only ever compiled
 * is a hypothesis: plans/material3-proof.md is the argument for why that is not good enough, and
 * this file is the answer to it.
 *
 * **Three rules, and each one is load-bearing for a drill.**
 *
 * 1. *One section at a time.* The catalogue is long enough that a single scrolling screen would
 *    put its own contents out of reach, and the Android accessibility drill has twice reported "the
 *    control was never reachable" about a screen that had merely grown. A chip picks a section; the
 *    rest is not composed. `AboutScreen.kt` records what that cost the first two times.
 *
 * 2. *Every control has a witness.* Beside each one is a line of text whose content is a function
 *    of that control's state -- `m3.switch=on`, `m3.button=3`, `m3.slider=0.60`. Four clients read
 *    a screen through four different instruments (an accessibility tree on Android, VoiceOver's
 *    elements on iOS, a browser's accessibility tree on the web, a render transcript on the
 *    desktop), and a line of text is the one observation all four can make. The claim a drill
 *    grades is therefore always the same sentence: *the control was found by its label, activated
 *    the way an assistive technology activates it, and the screen changed.* That is AGENTS.md
 *    section 1.5 -- prefer the consequence to the property that ought to imply it -- as a screen
 *    design rather than as advice.
 *
 * 3. *The witnesses are primitives; the labels are Material.* A witness is the instrument, so it
 *    is built from segment 0, which every client has had since the beginning and which is not the
 *    thing under test. The text *inside* each Material component -- a button's label, a list item's
 *    headline -- is Material 3's own `Text`. So a client that lacks the tier loses the labels and
 *    keeps the witnesses, and `M1` fails with a legible reason instead of passing on a screen full
 *    of placeholders.
 *
 * Icons come from the primitive tier, because Material 3's own `Icon` takes a `Painter` or an
 * `ImageVector` and no asset crosses this boundary (ADR-003). A Material component whose slot holds
 * a segment-0 component is itself worth grading, and `M6` does.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.dogwood.compose.Arrangement
import dev.dogwood.compose.Box
import dev.dogwood.compose.Column
import dev.dogwood.compose.HorizontalAlignment
import dev.dogwood.compose.Icon
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.Row
import dev.dogwood.compose.ScrollArea
import dev.dogwood.compose.SectionHeader
import dev.dogwood.compose.Spacer
import dev.dogwood.compose.Text
import dev.dogwood.compose.TextAlign
import dev.dogwood.compose.TextDecoration
import dev.dogwood.compose.TextOverflow
import dev.dogwood.compose.VerticalAlignment
import dev.dogwood.compose.FontWeight
import dev.dogwood.compose.dp
import dev.dogwood.compose.sp
import dev.dogwood.compose.contentDescription
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.height
import dev.dogwood.compose.padding
import dev.dogwood.compose.size
import dev.dogwood.compose.testTag
import dev.dogwood.compose.width
import dev.dogwood.compose.material3.*
import dev.dogwood.compose.material3.Text as M3Text

/**
 * The sections, in the order the chips offer them.
 *
 * `MaterialScreenCoverageTest` composes every one and counts the distinct Material 3 widget tags
 * that reach the wire, so this list is also the list that test walks. A section added here is
 * measured there without anybody remembering to.
 */
val MATERIAL_SECTIONS: List<Pair<String, String>> = listOf(
  "buttons" to "Buttons",
  "selection" to "Selection",
  "chips" to "Chips",
  "cards" to "Cards",
  "progress" to "Progress",
  "appbars" to "App bars",
  "navigation" to "Navigation",
  "tabs" to "Tabs",
  "dialogs" to "Dialogs",
  "sheets" to "Sheets",
)

@Composable
fun MaterialScreen() {
  // Saveable, so the section survives a code update as well as a recomposition -- the same
  // property `AppShell` gives the tab, and for the same reason: an over-the-air update should not
  // move the user.
  var section by rememberSaveable(key = "materialSection", stateSaver = autoSaver()) {
    mutableStateOf("buttons")
  }

  ScrollArea(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12), verticalArrangement = Arrangement.spacedBy(10)) {
      SectionHeader(
        title = "Material 3, generated",
        description = "Segment 255, version 10900 — Material 3 1.9.0, the version this host " +
          "resolves. Every control below was generated from the library's own sources. Pick a " +
          "section; each one names what it changed.",
      )

      /*
       * The picker is `FilterChip`, not a row of buttons, because navigating the catalogue should
       * itself be a use of the thing being demonstrated. It is also the first control every drill
       * touches: if the chips do not work, nothing below them can be reached, and the failure is
       * unambiguous rather than mysterious.
       */
      /*
       * Wrapped rows, not one scrolling row, and this is the third shape the picker has had.
       *
       * Ten chips do not fit across a phone. A plain `Row` clipped the last five and the Android
       * drill reported five sections as unreachable; a `HorizontalList` made them reachable in
       * principle and flaky in practice, because a lazy row composes only what is on screen, so
       * off-screen chips are not merely invisible to a drill -- they do not exist for it to find,
       * and on iOS `accessibilityScroll` did not move the row at all.
       *
       * Three rows of four always exist, are always laid out, and are reached by the vertical
       * scrolling every client already handles. It is also what Material itself recommends for a
       * filter set, which is the sort of agreement worth noticing after arriving from the other
       * direction.
       */
      for (row in MATERIAL_SECTIONS.chunked(4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
          for ((id, label) in row) {
            FilterChip(
              selected = id == section,
              onClick = { section = id },
              label = { M3Text(label) },
              modifier = Modifier.testTag("m3.section.$id"),
            )
          }
        }
      }
      Text("m3.section=$section", modifier = Modifier.testTag("m3.section"))

      HorizontalDivider(modifier = Modifier.fillMaxWidth())

      MaterialSection(section)
    }
  }
}

/**
 * One section, by name.
 *
 * Separate from [MaterialScreen] so that `MaterialScreenCoverageTest` can compose each section
 * without driving a chip, and so a drill can be pointed at one.
 *
 * [openEverything] forces the things a user opens -- the four dialogs, the sheet, the menu -- to
 * compose at once. It exists for the coverage count, which has to be a count of what this screen
 * *can* compose rather than of what happens to be on screen at rest; a dialog nobody opened would
 * otherwise be a component the catalogue claims and never sends. It is never true on a real client.
 */
@Composable
internal fun MaterialSection(section: String, openEverything: Boolean = false) {
  when (section) {
    "buttons" -> ButtonsSection()
    "selection" -> SelectionSection()
    "chips" -> ChipsSection()
    "cards" -> CardsSection()
    "progress" -> ProgressSection()
    "appbars" -> AppBarsSection()
    "navigation" -> NavigationSection()
    "tabs" -> TabsSection()
    "dialogs" -> DialogsSection(openEverything)
    "sheets" -> SheetsSection(openEverything)
  }
}

// -------------------------------------------------------------------------------------------
// Buttons
// -------------------------------------------------------------------------------------------

@Composable
private fun ButtonsSection() {
  var filled by remember { mutableStateOf(0) }
  var elevated by remember { mutableStateOf(0) }
  var tonal by remember { mutableStateOf(0) }
  var outlined by remember { mutableStateOf(0) }
  var text by remember { mutableStateOf(0) }
  var icons by remember { mutableStateOf(0) }
  var floating by remember { mutableStateOf(0) }
  var toggled by remember { mutableStateOf(false) }
  var tonalToggled by remember { mutableStateOf(false) }
  var outlinedToggled by remember { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      Button(onClick = { filled += 1 }) { M3Text("Filled") }
      ElevatedButton(onClick = { elevated += 1 }) { M3Text("Elevated") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      FilledTonalButton(onClick = { tonal += 1 }) { M3Text("Tonal") }
      OutlinedButton(onClick = { outlined += 1 }) { M3Text("Outlined") }
      TextButton(onClick = { text += 1 }) { M3Text("Text button") }
    }
    Text(
      "m3.buttons=$filled/$elevated/$tonal/$outlined/$text",
      modifier = Modifier.testTag("m3.buttons"),
    )

    /*
     * One of each disabled, with nothing else different.
     *
     * `enabled` is an affordance parameter (ADR-069), which means a client that cannot read it
     * withholds the control rather than drawing an enabled one -- so a disabled button is not
     * decoration here, it is the case where being wrong would let a user press something a newer
     * payload meant to forbid.
     */
    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      Button(onClick = { filled += 100 }, enabled = false) { M3Text("Filled off") }
      OutlinedButton(onClick = { outlined += 100 }, enabled = false) { M3Text("Outlined off") }
    }

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      FilledIconButton(onClick = { icons += 1 }) {
        Icon(name = "star", contentDescription = "Save this")
      }
      FilledTonalIconButton(onClick = { icons += 1 }) {
        Icon(name = "bookmark", contentDescription = "Bookmark this")
      }
      OutlinedIconButton(onClick = { icons += 1 }) {
        Icon(name = "search", contentDescription = "Search")
      }
    }
    Text("m3.iconbuttons=$icons", modifier = Modifier.testTag("m3.iconbuttons"))

    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      FilledIconToggleButton(checked = toggled, onCheckedChange = { toggled = it }) {
        Icon(name = if (toggled) "bookmark" else "bookmarkBorder", contentDescription = "Keep")
      }
      FilledTonalIconToggleButton(checked = tonalToggled, onCheckedChange = { tonalToggled = it }) {
        Icon(name = "check", contentDescription = "Done")
      }
      OutlinedIconToggleButton(checked = outlinedToggled, onCheckedChange = { outlinedToggled = it }) {
        Icon(name = "filter", contentDescription = "Filter")
      }
    }
    Text(
      "m3.icontoggles=${on(toggled)}/${on(tonalToggled)}/${on(outlinedToggled)}",
      modifier = Modifier.testTag("m3.icontoggles"),
    )

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      FloatingActionButton(onClick = { floating += 1 }) {
        Icon(name = "check", contentDescription = "Confirm")
      }
      SmallFloatingActionButton(onClick = { floating += 1 }) {
        Icon(name = "close", contentDescription = "Dismiss")
      }
      LargeFloatingActionButton(onClick = { floating += 1 }) {
        Icon(name = "star", contentDescription = "Favourite")
      }
    }
    ExtendedFloatingActionButton(onClick = { floating += 1 }) { M3Text("Extended action") }
    ExtendedFloatingActionButton(
      text = { M3Text("Extended with icon") },
      icon = { Icon(name = "flight", contentDescription = null) },
      onClick = { floating += 1 },
    )
    Text("m3.fab=$floating", modifier = Modifier.testTag("m3.fab"))
  }
}

// -------------------------------------------------------------------------------------------
// Selection
// -------------------------------------------------------------------------------------------

@Composable
private fun SelectionSection() {
  var checked by remember { mutableStateOf(false) }
  var switched by remember { mutableStateOf(true) }
  var choice by remember { mutableStateOf("second") }
  var volume by remember { mutableStateOf(0.4f) }
  var stepped by remember { mutableStateOf(2f) }
  var single by remember { mutableStateOf(0) }
  var multi by remember { mutableStateOf(setOf(1)) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    Row(verticalAlignment = VerticalAlignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6)) {
      /*
       * A description on the control itself, not only the text beside it.
       *
       * A `Checkbox` composes no text, so without this the platform announces an anonymous
       * checkable control and a person using a screen reader is told "checkbox, not ticked" with
       * no idea of what. The label beside it is a separate node and is not read as its name. This
       * is also what lets every drill find the control rather than guessing by position, which is
       * how the first Android run ticked a chip instead.
       */
      Checkbox(
        checked = checked,
        onCheckedChange = { checked = it },
        modifier = Modifier.contentDescription("Send me the summary"),
      )
      M3Text("Send me the summary")
    }
    Text("m3.checkbox=${on(checked)}", modifier = Modifier.testTag("m3.checkbox"))

    Row(verticalAlignment = VerticalAlignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6)) {
      // `thumbContent` is a slot on a control most people think of as having no content, which is
      // exactly the sort of thing a generated binding either gets right or gets silently wrong.
      Switch(
        checked = switched,
        onCheckedChange = { switched = it },
        thumbContent = { Icon(name = "check", contentDescription = null, sizeDp = 14) },
        modifier = Modifier.contentDescription("Background refresh"),
      )
      M3Text("Background refresh")
    }
    Text("m3.switch=${on(switched)}", modifier = Modifier.testTag("m3.switch"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    for ((id, label) in listOf("first" to "Economy", "second" to "Premium", "third" to "Business")) {
      Row(verticalAlignment = VerticalAlignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6)) {
        RadioButton(
          selected = choice == id,
          onClick = { choice = id },
          modifier = Modifier.contentDescription(label),
        )
        M3Text(label)
      }
    }
    Text("m3.radio=$choice", modifier = Modifier.testTag("m3.radio"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    /*
     * The slider that could not be called until this screen tried.
     *
     * Material 3 declares two `Slider` overloads whose guest-visible parameters are the same two
     * optionals in the opposite order. Both used to be generated, both compiled, and every call
     * that supplied only `value` and `onValueChange` was an overload-resolution ambiguity -- so
     * `Slider` was unreachable from any payload and nothing said so. The classifier's key ignores
     * parameter order now (ADR-073), which makes the second overload one component with the first.
     */
    M3Text("Volume")
    Slider(
      value = volume,
      onValueChange = { volume = it },
      modifier = Modifier.fillMaxWidth().contentDescription("Volume slider"),
    )
    Text("m3.slider=${twoPlaces(volume)}", modifier = Modifier.testTag("m3.slider"))

    M3Text("Seats, in whole numbers")
    Slider(
      value = stepped,
      onValueChange = { stepped = it },
      steps = 3,
      modifier = Modifier.fillMaxWidth().contentDescription("Seats slider"),
    )
    Text("m3.steps=${twoPlaces(stepped)}", modifier = Modifier.testTag("m3.steps"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    /*
     * `SegmentedButton` itself is an extension on a scope this boundary cannot cross, so it is not
     * bound and the rows hold ordinary buttons instead. The reference says so; this is what the
     * honest version looks like on a screen, rather than a row quietly left out of the catalogue.
     */
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      for (index in 0..2) {
        if (index == single) {
          Button(onClick = { single = index }) { M3Text("Leg ${index + 1}") }
        } else {
          OutlinedButton(onClick = { single = index }) { M3Text("Leg ${index + 1}") }
        }
      }
    }
    Text("m3.segmented.single=$single", modifier = Modifier.testTag("m3.segmented.single"))

    MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      for (index in 0..2) {
        val chosen = index in multi
        val toggle = { multi = if (chosen) multi - index else multi + index }
        if (chosen) {
          Button(onClick = toggle) { M3Text("Bag ${index + 1}") }
        } else {
          OutlinedButton(onClick = toggle) { M3Text("Bag ${index + 1}") }
        }
      }
    }
    Text(
      "m3.segmented.multi=${multi.sorted().joinToString("")}",
      modifier = Modifier.testTag("m3.segmented.multi"),
    )
  }
}

// -------------------------------------------------------------------------------------------
// Chips
// -------------------------------------------------------------------------------------------

@Composable
private fun ChipsSection() {
  var assists by remember { mutableStateOf(0) }
  var filters by remember { mutableStateOf(setOf("direct")) }
  var input by remember { mutableStateOf(true) }
  var suggestions by remember { mutableStateOf(0) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      AssistChip(
        onClick = { assists += 1 },
        label = { M3Text("Add to trip") },
        leadingIcon = { Icon(name = "star", contentDescription = null, sizeDp = 18) },
      )
      ElevatedAssistChip(onClick = { assists += 1 }, label = { M3Text("Share") })
    }
    Text("m3.assist=$assists", modifier = Modifier.testTag("m3.assist"))

    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      for (id in listOf("direct", "morning")) {
        val chosen = id in filters
        FilterChip(
          selected = chosen,
          onClick = { filters = if (chosen) filters - id else filters + id },
          label = { M3Text(if (id == "direct") "Direct only" else "Morning") },
          leadingIcon = if (chosen) {
            { Icon(name = "check", contentDescription = null, sizeDp = 18) }
          } else {
            null
          },
        )
      }
      ElevatedFilterChip(
        selected = "elevated" in filters,
        onClick = {
          filters = if ("elevated" in filters) filters - "elevated" else filters + "elevated"
        },
        label = { M3Text("Lie-flat") },
      )
    }
    Text(
      "m3.filters=${filters.sorted().joinToString(",")}",
      modifier = Modifier.testTag("m3.filters"),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      InputChip(
        selected = input,
        onClick = { input = !input },
        label = { M3Text("Tokyo") },
        avatar = { Icon(name = "location", contentDescription = null, sizeDp = 18) },
        trailingIcon = { Icon(name = "close", contentDescription = "Remove Tokyo", sizeDp = 18) },
      )
    }
    Text("m3.input=${on(input)}", modifier = Modifier.testTag("m3.input"))

    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      SuggestionChip(
        onClick = { suggestions += 1 },
        label = { M3Text("Try Kyoto") },
        icon = { Icon(name = "flight", contentDescription = null, sizeDp = 18) },
      )
      ElevatedSuggestionChip(onClick = { suggestions += 1 }, label = { M3Text("Try Osaka") })
    }
    Text("m3.suggestions=$suggestions", modifier = Modifier.testTag("m3.suggestions"))
  }
}

// -------------------------------------------------------------------------------------------
// Cards and lists
// -------------------------------------------------------------------------------------------

@Composable
private fun CardsSection() {
  var opened by remember { mutableStateOf(0) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12), verticalArrangement = Arrangement.spacedBy(4)) {
        M3Text("A plain card")
        M3Text("Its slot holds this column, composed by the host inside the library's own surface.")
      }
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12)) { M3Text("An elevated card") }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12)) { M3Text("An outlined card") }
    }

    // The clickable overloads. Same names, different components: an onClick makes a card a control
    // with a role, and a screen reader says so.
    Card(onClick = { opened += 1 }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12)) { M3Text("Open the itinerary") }
    }
    ElevatedCard(onClick = { opened += 1 }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12)) { M3Text("Open the receipt") }
    }
    OutlinedCard(onClick = { opened += 1 }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12)) { M3Text("Open the policy") }
    }
    Text("m3.cards=$opened", modifier = Modifier.testTag("m3.cards"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    // Every slot filled, because a list item with five slots is five chances for a generated
    // binding to compose a child into the wrong one.
    ListItem(
      headlineContent = { M3Text("Haneda to Kansai") },
      overlineContent = { M3Text("Tomorrow") },
      supportingContent = { M3Text("2 hours 5 minutes, direct") },
      leadingContent = { Icon(name = "flight", contentDescription = null) },
      trailingContent = { M3Text("¥18,400") },
    )
    ListItem(headlineContent = { M3Text("Hotel Granvia") }, leadingContent = { Icon(name = "hotel", contentDescription = null) })

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    Row(horizontalArrangement = Arrangement.spacedBy(16), verticalAlignment = VerticalAlignment.CenterVertically) {
      BadgedBox(badge = { Badge { M3Text("3") } }) {
        Icon(name = "bookmark", contentDescription = "Saved trips")
      }
      Badge()
      Label(label = { M3Text("A label's own slot") }) { M3Text("and its content") }
    }

    Row(verticalAlignment = VerticalAlignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8)) {
      M3Text("Left")
      VerticalDivider(modifier = Modifier.height(20))
      M3Text("Right")
    }
  }
}

// -------------------------------------------------------------------------------------------
// Progress and text
// -------------------------------------------------------------------------------------------

@Composable
private fun ProgressSection() {
  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12), verticalAlignment = VerticalAlignment.CenterVertically) {
      CircularProgressIndicator()
      CircularProgressIndicator(strokeWidth = 6.dp)
    }
    Text("m3.progress=shown", modifier = Modifier.testTag("m3.progress"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    /*
     * Material 3's own `Text`, with the properties a payload may set on it. Each one is a reader
     * on the host side that takes a closed set of names across the wire, and a name this client
     * predates is reported as `UNKNOWN_NAME` rather than guessed at.
     */
    M3Text("Heavy and large", fontWeight = FontWeight.Bold, fontSize = 22.sp)
    M3Text("Struck through", textDecoration = TextDecoration.LineThrough)
    M3Text("Centred in the width", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    M3Text(
      "A long line that is not allowed to wrap, so the host has to decide what to do with what " +
        "is left of it, and the answer is an ellipsis rather than a clipped word.",
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text("m3.text=styled", modifier = Modifier.testTag("m3.text"))
  }
}

// -------------------------------------------------------------------------------------------
// App bars
// -------------------------------------------------------------------------------------------

@Composable
private fun AppBarsSection() {
  var actions by remember { mutableStateOf(0) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    TopAppBar(
      title = { M3Text("Small bar") },
      navigationIcon = { FilledIconButton(onClick = { actions += 1 }) { Icon(name = "arrowBack", contentDescription = "Go back") } },
      actions = { TextButton(onClick = { actions += 1 }) { M3Text("Edit") } },
    )
    CenterAlignedTopAppBar(
      title = { M3Text("Centred bar") },
      actions = { TextButton(onClick = { actions += 1 }) { M3Text("Share") } },
    )
    MediumTopAppBar(
      title = { M3Text("Medium bar") },
      navigationIcon = { FilledIconButton(onClick = { actions += 1 }) { Icon(name = "close", contentDescription = "Close") } },
    )
    LargeTopAppBar(title = { M3Text("Large bar") })
    Text("m3.appbar=$actions", modifier = Modifier.testTag("m3.appbar"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    BottomAppBar(
      actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8), verticalAlignment = VerticalAlignment.CenterVertically) {
          TextButton(onClick = { actions += 1 }) { M3Text("Bottom action") }
        }
      },
      floatingActionButton = {
        FloatingActionButton(onClick = { actions += 1 }) { Icon(name = "check", contentDescription = "Apply") }
      },
    )
    BottomAppBar {
      Row(modifier = Modifier.padding(8), verticalAlignment = VerticalAlignment.CenterVertically) {
        M3Text("A bottom bar that is only a slot")
      }
    }
  }
}

// -------------------------------------------------------------------------------------------
// Navigation
// -------------------------------------------------------------------------------------------

@Composable
private fun NavigationSection() {
  var bar by remember { mutableStateOf(0) }
  var rail by remember { mutableStateOf(1) }
  var wide by remember { mutableStateOf(0) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    ShortNavigationBar {
      for ((index, entry) in NAV_ENTRIES.withIndex()) {
        ShortNavigationBarItem(
          selected = index == bar,
          onClick = { bar = index },
          icon = { Icon(name = entry.second, contentDescription = null) },
          label = { M3Text(entry.first) },
        )
      }
    }
    Text("m3.navbar=$bar", modifier = Modifier.testTag("m3.navbar"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    /*
     * Each rail gets its own full-width container, and that is a correction rather than a layout
     * preference.
     *
     * The first version put the plain rail and the wide rail side by side in fixed-width boxes,
     * 90 and 150 density-independent pixels. Android and the web laid that out; iOS crashed the
     * whole application with `maxWidth must be >= than minWidth`, because a wide navigation rail
     * asks for more width than 150 and a maximum below a minimum is not a constraint any platform
     * can satisfy. Bounding a component's height is fair -- otherwise a rail becomes the viewport
     * and the drill cannot reach anything past it -- but bounding its width below what it needs is
     * asking for a contradiction, and only one of the three clients said so.
     */
    Box(modifier = Modifier.height(200).fillMaxWidth()) {
      NavigationRail(header = { Icon(name = "flight", contentDescription = null) }) {
        for ((index, entry) in NAV_ENTRIES.withIndex()) {
          NavigationRailItem(
            selected = index == rail,
            onClick = { rail = index },
            icon = { Icon(name = entry.second, contentDescription = null) },
            label = { M3Text(entry.first) },
          )
        }
      }
    }
    /*
     * The two wide rails are NOT on this screen, and their absence is a finding rather than an
     * oversight.
     *
     * `WideNavigationRail` and `ModalWideNavigationRail` bind, compile and render under the tier's
     * own render tests on three targets. On the iOS simulator they crash the whole application from
     * inside `WideNavigationRailLayout`'s measure pass -- `maxWidth must be >= than minWidth` --
     * whether they are given a fixed width or the full one, while Android and the web lay the same
     * screen out without complaint. That is a Compose Multiplatform layout failure rather than a
     * Dogwood binding failure: nothing about the wire or the generated code differs between the
     * three clients.
     *
     * A sample that crashes one client is not a sample. They are excluded here, the coverage test's
     * count is two lower for it, and the observation is drafted in tools/upstream-reports/README.md
     * with the frame that raised it. The plain `NavigationRail` above is the same family and works
     * everywhere.
     */
    Text("m3.rail=$rail/$wide", modifier = Modifier.testTag("m3.rail"))
  }
}

private val NAV_ENTRIES = listOf(
  "Flights" to "flight",
  "Stays" to "hotel",
  "Saved" to "bookmark",
)

// -------------------------------------------------------------------------------------------
// Tabs
// -------------------------------------------------------------------------------------------

@Composable
private fun TabsSection() {
  var primary by remember { mutableStateOf(0) }
  var secondary by remember { mutableStateOf(1) }
  var scrollable by remember { mutableStateOf(0) }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    PrimaryTabRow(selectedTabIndex = primary) {
      for ((index, label) in listOf("Outbound", "Return", "Extras").withIndex()) {
        Tab(
          selected = index == primary,
          onClick = { primary = index },
          text = { M3Text(label) },
          icon = { Icon(name = "flight", contentDescription = null, sizeDp = 18) },
        )
      }
    }
    Text("m3.tabs.primary=$primary", modifier = Modifier.testTag("m3.tabs.primary"))

    SecondaryTabRow(selectedTabIndex = secondary) {
      for ((index, label) in listOf("Seats", "Bags").withIndex()) {
        // The content overload: a tab whose whole body the payload composes.
        Tab(selected = index == secondary, onClick = { secondary = index }) {
          Column(modifier = Modifier.padding(8), horizontalAlignment = HorizontalAlignment.CenterHorizontally) {
            M3Text(label)
          }
        }
      }
      LeadingIconTab(
        selected = secondary == 2,
        onClick = { secondary = 2 },
        text = { M3Text("Meals") },
        icon = { Icon(name = "star", contentDescription = null, sizeDp = 18) },
      )
    }
    Text("m3.tabs.secondary=$secondary", modifier = Modifier.testTag("m3.tabs.secondary"))

    PrimaryScrollableTabRow(selectedTabIndex = scrollable) {
      for ((index, label) in SCROLLING_TABS.withIndex()) {
        Tab(selected = index == scrollable, onClick = { scrollable = index }, text = { M3Text(label) })
      }
    }
    SecondaryScrollableTabRow(selectedTabIndex = scrollable) {
      for ((index, label) in SCROLLING_TABS.withIndex()) {
        Tab(selected = index == scrollable, onClick = { scrollable = index }, text = { M3Text("$label ") })
      }
    }
    Text("m3.tabs.scrollable=$scrollable", modifier = Modifier.testTag("m3.tabs.scrollable"))
  }
}

private val SCROLLING_TABS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")

// -------------------------------------------------------------------------------------------
// Dialogs
// -------------------------------------------------------------------------------------------

@Composable
private fun DialogsSection(openEverything: Boolean = false) {
  var open by remember { mutableStateOf("") }
  var outcome by remember { mutableStateOf("none") }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      Button(onClick = { open = "alert" }) { M3Text("Open alert") }
      OutlinedButton(onClick = { open = "basic" }) { M3Text("Open basic") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = VerticalAlignment.CenterVertically) {
      OutlinedButton(onClick = { open = "date" }) { M3Text("Open date") }
      OutlinedButton(onClick = { open = "time" }) { M3Text("Open time") }
    }
    Text("m3.dialog=${if (open.isEmpty()) "closed" else open}", modifier = Modifier.testTag("m3.dialog"))
    Text("m3.dialog.outcome=$outcome", modifier = Modifier.testTag("m3.dialog.outcome"))

    if (openEverything || open == "alert") {
      AlertDialog(
        onDismissRequest = { open = ""; outcome = "dismissed" },
        icon = { Icon(name = "info", contentDescription = null) },
        title = { M3Text("Cancel this booking?") },
        text = { M3Text("The fare is refundable until midnight.") },
        confirmButton = {
          Button(onClick = { open = ""; outcome = "confirmed" }) { M3Text("Cancel booking") }
        },
        dismissButton = {
          TextButton(onClick = { open = ""; outcome = "kept" }) { M3Text("Keep it") }
        },
      )
    }
    if (openEverything || open == "basic") {
      BasicAlertDialog(onDismissRequest = { open = ""; outcome = "dismissed" }) {
        Card {
          Column(modifier = Modifier.padding(16), verticalArrangement = Arrangement.spacedBy(8)) {
            M3Text("A dialog the payload composes entirely")
            Button(onClick = { open = ""; outcome = "basic-closed" }) { M3Text("Close this") }
          }
        }
      }
    }
    if (openEverything || open == "date") {
      DatePickerDialog(
        onDismissRequest = { open = ""; outcome = "dismissed" },
        confirmButton = { Button(onClick = { open = ""; outcome = "date-picked" }) { M3Text("Pick the date") } },
        dismissButton = { TextButton(onClick = { open = ""; outcome = "kept" }) { M3Text("Not now") } },
      ) {
        Column(modifier = Modifier.padding(16)) { M3Text("A date picker's own slot") }
      }
    }
    if (openEverything || open == "time") {
      TimePickerDialog(
        onDismissRequest = { open = ""; outcome = "dismissed" },
        title = { M3Text("Departure time") },
        confirmButton = { Button(onClick = { open = ""; outcome = "time-picked" }) { M3Text("Pick the time") } },
        dismissButton = { TextButton(onClick = { open = ""; outcome = "kept" }) { M3Text("Not now") } },
      ) {
        Column(modifier = Modifier.padding(16)) { M3Text("A time picker's own slot") }
      }
    }
  }
}

// -------------------------------------------------------------------------------------------
// Sheets, drawers and menus
// -------------------------------------------------------------------------------------------

@Composable
private fun SheetsSection(openEverything: Boolean = false) {
  var sheet by remember { mutableStateOf(false) }
  var drawer by remember { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  var chosen by remember { mutableStateOf("none") }

  Column(verticalArrangement = Arrangement.spacedBy(8)) {
    Button(onClick = { sheet = true }) { M3Text("Open the sheet") }
    Text("m3.sheet=${on(sheet)}", modifier = Modifier.testTag("m3.sheet"))
    if (openEverything || sheet) {
      ModalBottomSheet(
        onDismissRequest = { sheet = false },
        dragHandle = { VerticalDragHandle() },
      ) {
        Column(modifier = Modifier.padding(16), verticalArrangement = Arrangement.spacedBy(8)) {
          M3Text("Fare conditions")
          Button(onClick = { sheet = false; chosen = "sheet" }) { M3Text("Close the sheet") }
        }
      }
    }

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    /*
     * A drawer occupies its whole container, so each one is given a bounded container. That is a
     * legitimate use -- a drawer inside a pane -- and the only one a drill can reach without the
     * section becoming a viewport.
     */
    Box(modifier = Modifier.height(220).fillMaxWidth()) {
      ModalNavigationDrawer(
        drawerContent = {
          ModalDrawerSheet {
            Column(modifier = Modifier.padding(12), verticalArrangement = Arrangement.spacedBy(6)) {
              M3Text("Modal drawer sheet")
              TextButton(onClick = { drawer = false; chosen = "modal-drawer" }) { M3Text("Choose modal") }
            }
          }
        },
      ) {
        Column(modifier = Modifier.padding(12), verticalArrangement = Arrangement.spacedBy(6)) {
          M3Text("Behind the modal drawer")
          Button(onClick = { drawer = !drawer }) { M3Text("Toggle the drawer flag") }
        }
      }
    }
    Text("m3.drawer=${on(drawer)}", modifier = Modifier.testTag("m3.drawer"))

    Box(modifier = Modifier.height(160).fillMaxWidth()) {
      DismissibleNavigationDrawer(
        drawerContent = {
          DismissibleDrawerSheet {
            Column(modifier = Modifier.padding(12)) { M3Text("Dismissible drawer sheet") }
          }
        },
      ) {
        Column(modifier = Modifier.padding(12)) { M3Text("Beside the dismissible drawer") }
      }
    }

    Box(modifier = Modifier.height(140).fillMaxWidth()) {
      PermanentNavigationDrawer(
        drawerContent = {
          PermanentDrawerSheet {
            Column(modifier = Modifier.padding(12)) { M3Text("Permanent drawer sheet") }
          }
        },
      ) {
        Column(modifier = Modifier.padding(12)) { M3Text("Beside the permanent drawer") }
      }
    }

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
      Column(verticalArrangement = Arrangement.spacedBy(4)) {
        Button(onClick = { menu = !menu }) { M3Text("Cabin class") }
        if (openEverything || menu) {
          for (option in listOf("Economy", "Premium", "Business")) {
            DropdownMenuItem(
              text = { M3Text(option) },
              onClick = { chosen = option; menu = false },
              leadingIcon = { Icon(name = "check", contentDescription = null, sizeDp = 18) },
            )
          }
        }
      }
    }
    Text("m3.menu=$chosen", modifier = Modifier.testTag("m3.menu"))

    HorizontalDivider(modifier = Modifier.fillMaxWidth())

    // Laid out statically rather than posted: `SnackbarHost` needs a host-side state holder, which
    // is `SnackbarArea` on segment 0 (the About screen demonstrates that one). This is the
    // library's own surface, composed where it can be read.
    Snackbar(
      action = { TextButton(onClick = { chosen = "undone" }) { M3Text("Undo") } },
      dismissAction = { TextButton(onClick = { chosen = "dismissed" }) { M3Text("Dismiss") } },
    ) {
      M3Text("Seat released")
    }
    Spacer(modifier = Modifier.size(8))
  }
}

// -------------------------------------------------------------------------------------------

private fun on(value: Boolean): String = if (value) "on" else "off"

/**
 * Two decimal places without a formatter.
 *
 * `Formats` on segment 0 asks the *host* to format, which is the right answer for anything a user
 * reads in their own locale -- and exactly the wrong answer for a witness, whose whole job is to
 * be the same string on four clients in four locales. A drill comparing `0.60` with `0,60` would
 * be reporting on a decimal separator.
 */
private fun twoPlaces(value: Float): String {
  val hundredths = (value * 100).toInt()
  return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
