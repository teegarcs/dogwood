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

Apply the `dev.dogwood.guest` plugin to your payload module and these fail the build with the
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

## 10. What is not there

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
