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

- **Network** — the host's allow-list decides which hosts you may reach, and it **refuses everything
  by default**. That is deliberate: your payload is replaceable over the air without a store review,
  so an open network service inside it would be an exfiltration channel that ships in minutes. Ask
  the host team to add a host; do not look for a way around it.
- **Log, clock, analytics, feature flags** — injected, so they can be faked in a test and routed to
  whatever the application already uses.
- **Launch parameters** — the host names which screen it wants and hands it a JSON object. That is
  where an environment-specific address belongs: `10.0.2.2` on an Android emulator and `localhost`
  on an iOS simulator are the same machine, and only the host knows which name reaches it.

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

## 8. What is not there

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
