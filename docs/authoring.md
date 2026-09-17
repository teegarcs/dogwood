# Writing screens

For somebody writing payload code. The rules are few, and each exists because of something that
breaks without it — this page gives the reason with the rule, because a rule whose reason is missing
gets worked around.

Your screens are **ordinary Kotlin Compose**. Most of what you know transfers unchanged: `remember`,
state hoisting, `if`/`when` in composition, lists, layout. What follows is the delta.

---

## 1. The one idea

**Your code composes; the host draws.** Composition happens in a sandbox on the device and produces
*tree changes*, which the host replays against native Compose. So the boundary is crossed by data —
numbers, strings, enums — and never by a drawing instruction or a host object.

Two things follow that explain almost every rule below:

- **Crossing the boundary costs something**, so anything that would cross it *every frame* is
  refused rather than made slow.
- **The host is older than you are.** Your payload will run against installed applications built
  months earlier, against a component dictionary that has moved since.

## 2. What the build refuses, and what to write instead

Apply the `io.github.teegarcs.dogwood.guest` plugin to your payload module and these fail the build with the
replacement named. They are refused rather than left to fail on a device, because on a device they
fail as *slowness*, which is the hardest symptom to trace back to a cause.

| Refused | Because | Instead |
|---|---|---|
| `animateFloatAsState`, `animateDpAsState`, `animateColorAsState`, `animateIntAsState`, `animateValueAsState` | per-frame state in the guest: it ticks the boundary every frame it animates | declare a **target** — `Modifier.alpha(animate(target, spec))` — and the host runs the frames |
| `updateTransition`, `Animatable` | the same, with more ceremony | declare a target |
| `rememberInfiniteTransition` | per-frame state, forever, whether or not anything is watching | `oscillate(from, to, spec)` — one crossing, and the host repeats it |
| `withFrameNanos`, `withFrameMillis` | a guest frame loop is per-frame state by definition | declare what should change; let the host run the frames |
| `painterResource`, `imageResource`, `vectorResource` | the sandbox has no resources | `AsyncImage(url)`, `Icon(name)` |
| `stringResource` | the same | payload-carried string tables |
| `OutlinedTextField`, `BasicTextField`, `SecureTextField` and their kin; `SearchBar` and the other search bars | a controlled text field asks you what the text should be after **every keystroke**, across a boundary the next keystroke is racing | `TextInput`, or the `TextField(state = rememberTextFieldState())` wrapper over it — §4 |
| `TextField(value = …, onValueChange = …)` | the same: it is Compose's controlled overload | `TextField(state = …)` — same name, different argument, and the one this library gives you |

**The text fields are the one family on that list that does not compile anyway**, and they are on
it for a different reason from the rest. Nothing a payload can see declares `OutlinedTextField`, so
without the check you get "unresolved reference" — which names no replacement and reads like a
missing dependency, when the truth is that this architecture deliberately does not have one
([ADR-019](../adrs/layer-5/ADR-019-text-input.md): the host owns the buffer, you hold a
version-stamped mirror, §4). `TextField` is the exception that proves it: the name **does** resolve,
to the wrapper you should be calling, and only the `onValueChange` overload is refused.

**The plugin also reads your dependencies, not just your source.** `dogwoodGuestClasspathCheck`
fails on the artifacts these APIs live in — `animation-core` and its kin — because a payload that
depends on a helper library which depends on one of those has every frame-clock API one import
away, and a source scan cannot see it. The failure names the route the artifact took and the
`exclude` that removes it.

**The shape of the replacement is always the same: say what should be true, not what should happen
each frame.** An animation is a target and a specification; the host interpolates. That is one
crossing instead of sixty a second, and it is why animation on this architecture is not a
compromise.

## 3. State, and what survives what

Three lifetimes, and picking the wrong one is the most common real bug in payload code.

| | Survives recomposition | Survives a **code update** | Survives process death |
|---|---|---|---|
| `remember` | yes | no | no |
| `rememberSaveable` | yes | **yes** | yes, if the host saved |
| host-owned state (§4) | yes | yes | yes |

**A code update while a screen is live is the normal case here**, not an edge case — it is the whole
point of the architecture. `rememberSaveable` is what carries a half-filled form across one. Node
identity is preserved across the update, so `remember` survives a *recomposition* triggered by one;
what it does not survive is the payload actually being replaced.

## 4. State the host owns

Some state cannot live in your code at all, because the host owns the thing it describes: a scroll
position belongs to the scrolling container, focus belongs to the platform's focus system, a
snackbar's dismissal belongs to whatever drew it.

For these you get a **holder** — you declare intent, the host reports back:

```kotlin
val scroll = rememberScrollState()
ScrollArea(scroll = scroll) { ... }

scroll.animateScrollTo(0)             // you say where it should go
val where = scroll.offsetDp           // the host tells you where it is
val atEnd = scroll.offsetDp >= scroll.maxOffsetDp   // and how far there is to go
```

Four shapes exist today, and knowing which you are holding tells you what to expect:

| Shape | Example | What comes back |
|---|---|---|
| target **and** report | a list's visible range | a report on change |
| target only | focus | nothing — focus is something you *ask for* |
| continuous position with a declared quantum | scroll | a report every *n* density-independent pixels, so a drag is not a thousand crossings |
| a request that **answers** | a snackbar | the answer to the request you made, and not to a superseded one |

**The quantum is yours to declare.** A scroll holder reports every 48 density-independent pixels by
default; raise it if you only need coarse position, and understand that lowering it buys precision
with boundary crossings.

**Text is not in that table, and it is the one holder that could not use the pattern.** A text field's buffer
belongs to the host, where the typing is, and your `TextFieldState` is a **version-stamped mirror**
of it: the host counts user edits, every edit event carries that count, and a value you send stamped
older than the host's count is discarded — because the user has typed since, and the user wins. That
is why it is a version rather than a flat "the host always wins", which would make `state.clear()`
impossible. Masks, length limits and counters are declared once and applied host-side; your value is
always the raw one, so changing a mask cannot change what your validation reads.
[ADR-019](../adrs/layer-5/ADR-019-text-input.md) has the whole rule, including why a controlled
`TextField` is refused in §2 rather than made slow.

```kotlin
val card = rememberTextFieldState()
TextField(state = card, mask = "#### #### #### ####", keyboard = Keyboards.NUMBER, showCounter = true)
// card.text is digits. It never contains the spaces.
```

**A holder's report is edge-triggered.** It tells you when its value *changes*, which is the throttle
— so a fresh holder after a code update knows nothing until something moves. The host handles that
by keying its reporting on the guest generation; you do not have to, but it explains why a value can
read as "not yet told" rather than as a number.

## 5. Values the host resolves

Some parameters are typed `TextValue`, `Color` or `Shape` rather than `String` or `Int`. Those are
**recipes**, resolved on the host at draw time — because the answer depends on things only the host
knows: the locale, the theme, the platform's plural rules, the user's font scale.

```kotlin
Text(Formats.plural(nights, mapOf("one" to "# night", "other" to "# nights")))
```

Write the recipe, not the result. A number formatted in your code is formatted in the sandbox's
locale, which is not the user's — and the set of plural categories a language uses is not yours to
guess: `zero`, `one`, `two`, `few`, `many` and `other` are not all live everywhere, and the host
picks. A category your templates do not cover is reported rather than guessed at.

## 6. Talking to the outside

The host provides services; the payload has no ambient access to anything.

- **Network** — on Android and iOS the host's allow-list decides which hosts you may reach, and it
  **refuses everything by default**. That is deliberate: your payload is replaceable over the air
  without a store review, so an open network service inside it would be an exfiltration channel that
  ships in minutes. Ask the host team to add a host; do not look for a way around it.

  **On the web the enforcement is the browser's, not Dogwood's, and it is weaker.** Your code runs
  in a Worker inside the page's origin and calls `fetch` directly; what constrains it is the page's
  Content Security Policy. Write as if the allow-list applied — a payload that only reaches hosts
  the mobile clients allow is a payload that behaves the same everywhere — and tell whoever owns the
  page which `connect-src` you need.
- **Log, clock, analytics, feature flags** — injected, so they can be faked in a test and routed to
  whatever the application already uses.
- **Launch parameters** — the host names which screen it wants and hands it a JSON object. That is
  where an environment-specific address belongs: `10.0.2.2` on an Android emulator, `localhost` on an
  iOS simulator and the page's own origin on the web are all the same machine, and only the host
  knows which name reaches it.
- **Dictionary versions** — `LocalSegmentVersions` tells you what the client you are running on
  implements, which is how a payload uses a new component only where it exists. A client that
  reports nothing is a client every guest must assume is empty.

## 7. Writing for clients older than you

The rule that costs nothing to follow now and cannot be retrofitted:

- **Append to a surface; never reorder or remove.** Property tags are positional, so inserting a
  parameter renumbers everything after it — and a renumbered tag does not fail to render, it renders
  the wrong thing on a client one version behind. The lock beside your surface fails the build if you
  try; that is the lock working, not the lock being difficult.
- **Mark `@Affordance` on anything that governs what a user may do.** Marked parameters change how a
  client degrades: an old client meeting an unreadable *cosmetic* property ignores it and renders,
  and meeting an unreadable *affordance* property **withholds the whole control** — because one of
  the things you might have been saying is "this is disabled", and drawing an enabled-looking
  control that is meant to be disabled is worse than a gap.
- **A new component reaches nobody until the hosts ship.** Old clients render it as an inert
  placeholder that keeps its slot, and report it. Plan a component and a screen using it as two
  releases, not one.
- **A new enumeration entry is the same, one size down.** An entry crosses as its name, and an old
  client that meets a name it does not carry renders the parameter's declared default and reports
  it. Entries are append-only like tags, and adding one moves the segment version, so branch on
  `LocalSegmentVersions` before sending an entry the oldest client you support has not heard of.
- **Expect to be one version ahead and design for it.** Everything above is contained rather than
  fatal, and every containment is reported — [operating](operating.md) §4 is how a team sees that
  its payloads have moved ahead of its devices, and it is worth wiring before you need it.

## 8. The loop you live in

Both halves of a fast inner loop exist; neither was documented until the adoption audit asked.

**Live reload, with your state carried across.** Serve the *development* payload continuously:

```
./gradlew --continuous :your-guest:serveDevelopmentWebpackZipline
```

`--continuous` rebuilds on every save, the development bundle skips the size optimizer, and any
running host does the rest on its own: the shell polls the manifest every five seconds and swaps in
the new guest **carrying your `rememberSaveable` state with it** — the same machinery as a
production code update (claim `A7`), which is why editing a screen you are three taps deep into
puts you back three taps deep into the new code. Nothing to install, nothing to wire; it is the
default behaviour of every shell host pointed at the serve task's port.

**Testing a screen without a host.** Your screens are functions from state to a tree, and the tree
crosses as data — so a screen test is: compose against a fake host, pump one frame, decode what
crossed with the real decoder. All three pieces are public:

```kotlin
class RecordingHost : DogwoodHost {
  val batches = mutableListOf<String>()
  override fun sendChanges(positionalBatch: String) { batches += positionalBatch }
  override fun requestFrame() = Unit
  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
  override fun onUnknownEventNode(id: Id, tag: EventTag) = Unit
  override fun handleUncaughtException(exception: Throwable) = throw exception  // fine in a test
  override fun close() = Unit
}

@Test fun theHeadingRenders() {
  val host = RecordingHost()
  val composition = DogwoodComposition(
    host = host, initialConfiguration = HostEnvironment(),
    segmentVersions = emptyMap(), restoredState = null,
    services = HostServices.None, launchParams = JsonNull,
    content = { CheckoutScreen() },
  )
  composition.frame(0L)                                   // effects run on the frame after composition
  val batch = decodePositional(host.batches.first())      // the REAL decoder, from dogwood-protocol
  // assert on the changes: which widgets were created, what properties crossed
  composition.dispose()
}
```

What this asserts is what the engine's own guest tests assert: *what crossed the wire*, through the
real encoder and the real decoder — not what a screen looks like, which is the host's business and
graded by the host's own conformance suite.

## 9. Bringing an existing screen across

Your screens are Compose, and so are Dogwood's — but the *types* are Dogwood's, so moving a native
screen in is a port rather than a re-import. [`tools/import-migrator/migrate.py`](../tools/import-migrator/migrate.py)
does the mechanical half and, more usefully, names the other half instead of leaving you to find it
by compiling:

```
tools/import-migrator/migrate.py src/main/kotlin/checkout/ --write
```

- **Rewritten**: layout, material and unit imports that map one-for-one — same name, different
  package, unchanged signature. That criterion *is* the table.
- **Kept**: everything from `androidx.compose.runtime`. The guest runs the real Compose runtime, so
  `remember`, `LaunchedEffect` and `rememberSaveable` are the same symbols from the same package —
  reported as kept, so silence never has to be interpreted.
- **Left for you, with the reason**: widgets whose shape differs (`Button` takes a content slot;
  `PrimaryButton` takes a label — a slot per button is a slot per button on the wire), and APIs the
  sandbox does not have (`stringResource`, `animateFloatAsState`). These become `// MIGRATE:`
  comments rather than rewritten imports, because an import pointing at a symbol that does not
  exist makes the compiler blame a package when the real problem is a different API.

It exits non-zero while any decision is outstanding, so it can gate a migration script. Run it on a
screen already written for Dogwood and it does nothing at all — which is the control.

## 10. Composing your own components, and when that is not enough

**Look in the generated library tiers first.** Since [ADR-072](../adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md)
a payload can `import dev.dogwood.compose.material3.*` and call `Button`, `Switch`, `Card`,
`TopAppBar` and most of the rest of Material 3 with the library's own signatures, if the host
registered the tier — and the same for the `foundation`, `foundation.layout` and `androidx.ui`
tiers. [`tools/generator-v2/coverage.md`](../tools/generator-v2/coverage.md) is the list, generated
from the pinned sources; read the count there rather than from a sentence here, because it moves
whenever the libraries move or the generator learns a type. **Compose your own only for what is not
there.** That is the order — it used to be the other way round on this page, and a component you do
not have to declare is a component nobody has to keep in step.

**A default you do not set belongs to the host.** The generated bindings call the real library
function with the library on the *host's* classpath, so any optional parameter you leave out takes
that host's default, evaluated on the device. Set the parameter when you need one look;
[`upgrading-compose.md`](upgrading-compose.md) section 5 says what that means for a fleet
mid-upgrade.

**The worked example is `MaterialScreen.kt`** in `samples/slice-screens`, which every client
renders: sections composing the tier's bound components, every control with a line of text beside
it that says what it changed, and a test asserting a floor so the catalogue cannot fall behind what
the report claims. It is what the `M` conformance claims are graded against on a device.

The rule that decides whether a new component needs an app release: **can it be composed from
pieces the installed client already has?** If yes, write it as an ordinary `@Composable` in the
payload — it needs no surface entry, no tag, no registration — and it ships and updates with the
payload. The pieces are the primitive tier (`Text`, `Column`, `Row`, `Box`, `Spacer`, the lists
and the pager, with arrangement and alignment on the containers and weight, alignment, overflow,
size and decoration on text; twenty-seven modifiers including `clickable` on any node, `border`,
`offset`, `shadow`, per-side `padding`, size bounds, `contentDescription` and `testTag`) plus every
registered component the client carries. A status pill, a tab row, a stat tile, a tappable card,
a two-line list item are all payload code.

```kotlin
@Composable
fun StatusPill(label: String, tone: Color, onClick: () -> Unit) {
  Row(
    modifier = Modifier.clip(RoundedCornerShape(12)).border(1, tone).padding(horizontal = 10, vertical = 4).clickable(onClick = onClick),
    horizontalArrangement = Arrangement.spacedBy(6),
    verticalAlignment = VerticalAlignment.CenterVertically,
  ) {
    Box(Modifier.size(8).clip(CircleShape).background(tone))
    Text(label, fontWeight = FontWeight.Medium)
  }
}
```

If no — the component owns real drawing, a gesture, an animation the host must run frame by
frame, platform integration, or a live state holder — it is a surface entry on the host and rides a
release. When a *compositional* component cannot be written because a primitive is missing, that
primitive is the thing to add to segment 0, and it is one tag ([ADR-069](../adrs/layer-5/ADR-069-the-primitive-tier-is-the-lever.md)).

## 11. What is not there

Named so you look for the alternative rather than for the bug.

- **No per-frame anything.** §2 is not a temporary limitation; it is the shape of the architecture.
- **No resources.** No drawables, no string tables from the platform, no fonts you ship. Images come
  by URL, icons by name from the host's set, strings from tables you carry.
- **No arbitrary host calls.** You cannot pass a lambda that closes over a host object, because the
  boundary carries data.
- **No component the installed host lacks.** See §7.

---

Next: the **[component reference](api/)**, generated from the surface, for what you can actually
call and what each parameter's tag is.
