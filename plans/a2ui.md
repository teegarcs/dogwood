# A2UI: the host as a renderer, the dictionary as a catalogue, and a policy that says what an agent may ask for

Drafted 2026-09-29. Status: **plan, not started.** Nothing below has been implemented; every
"will" is a commitment, and every fact in §0 was read off commit `e5ade71` or off the A2UI
repository on the date given, not remembered.

Agent-to-User Interface (A2UI) is Google's open protocol in which a model emits a declarative
JavaScript Object Notation (JSON) description of a screen, drawn from a pre-agreed catalogue of
components, and a renderer on the device turns that description into native user interface (UI).
Dogwood was built for the opposite producer: a developer's compiled Kotlin, signed, delivered
over the air, and run in a sandbox. This plan makes Dogwood serve both producers with one host,
and it does so without weakening the contract that makes Dogwood worth having, because that
contract turns out to sit on the host side of the boundary already.

The reframing the whole plan rests on:

| | Capability | Permission |
|---|---|---|
| **What it is** | what the installed host can render | what one agent, on one surface, may ask for |
| **Fixed by** | the application build, through the dictionary | the product team, through a catalogue policy |
| **Who keeps all of it** | a compiled guest written by a developer | nobody; an agent always gets a subset |
| **Enforced where** | the lock at build time, the pre-flight at start | the renderer, per message, before anything is drawn |

Dogwood conflates the two today because it has only ever had one producer, and that producer
is trusted. An agent is not, and a model's output is not a versioning accident to degrade
gracefully but a proposal to accept or refuse. The house rules apply throughout: **done means
run**, on a device, with the verdict recorded where a check can re-read it; and a check earns
belief by being watched to fail without the fix.

The document is in three parts. Part 1 is the plan, with gates. Part 2 is the execution model,
what happens at runtime and where. Part 3 is the guide for use, written for the product team that
will configure this for their own needs; it describes the end state of the plan and says so.

---

## 0. What is known before a line is written

### 0.1 A2UI, as of 2026-09-24

Read from `github.com/google/A2UI` on `main` and the site built from its `docs/public/`.

**Versions.** `specification/` holds `v0_8`, `v0_9`, `v0_9_1` and `v1_0`. The README calls
v0.8 "legacy", v0.9.1 the "current production release", and v1.0 a "release candidate" that
"awaits broader renderer adoption". v0.9 renamed every message: `beginRendering` became
`createSurface`, `surfaceUpdate` became `updateComponents`, `dataModelUpdate` became
`updateDataModel`, `userAction` became `action`. v1.0 renamed "client" and "server" to "renderer"
and "agent", added four function-call messages, removed `theme`, and changed the media type to
`application/a2ui+json`. The homepage describes v1.0 features (`actionResponse`,
`surfaceProperties`) that do not appear in the v1.0 schema files. **The protocol is still
moving**, and that fact drives the decision in §1.4 about where the translator runs.

**Messages.** Agent to renderer (`specification/v1_0/json/agent_to_renderer.json`):
`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`, `callRendererFunction`,
`agentFunctionResponse`. Renderer to agent (`renderer_to_agent.json`): `action`, `error`,
`callAgentFunction`, `rendererFunctionResponse`. Every message carries a top-level `version`.
Components are a flat adjacency list: each has `id`, `component`, and containers name children
by id through `child` or `children`. Data binding is by JSON Pointer (Request for Comments (RFC) 6901) path. Errors
carry a `code` from `VALIDATION_FAILED`, `UNALLOWED_PARENT`, `UNALLOWED_CHILD`,
`INVALID_FUNCTION_CALL`, and one sentence of `message`.

**Catalogues.** A catalogue is a JSON Schema document (`specification/v1_0/json/catalog_definition.json`)
with a `catalogId`, a `components` map of name to schema, a `functions` map, and a
`protocolVersion`. The renderer advertises `supportedCatalogIds` in message metadata
(`renderer_capabilities.json`), ordered by preference; the agent picks one and locks it for the
surface's lifetime; an unresolvable id is an error with **no fallback**. Versioning is by id:
add freely, never delete, mark deprecated, issue a new id for a breaking change. v1.0 adds
per-component `allowedParents` and `allowedChildren`. The basic catalogue
(`specification/v0_9_1/catalogs/basic/catalog.json`) has eighteen components and fourteen
functions, listed in Part 2 §2.4. Its `rules.txt` is a list of required properties and nothing
more.

**How the model is checked.** The spec prescribes a prompt containing the desired UI, the
schema including the catalogue, and examples; then validate; then, if invalid, report the
errors back to the model in a subsequent prompt. The Python agent software development kit (SDK)
under `agent_sdks/python/a2ui_agent/src/a2ui/` has `schema/validator.py`, a streaming parser,
a `parser/payload_fixer.py` that "corrects common LLM output issues" (a large language model, LLM), and an Agent Development
Kit (ADK) toolset in which the model emits UI **as a tool call** whose arguments are the
messages. Nothing in the spec requires constrained decoding.

**Renderers.** First-party: React, Lit, Angular, and Flutter (external, `flutter/genui`).
`docs/public/reference/renderers.md` lists SwiftUI and Jetpack Compose as "🚧 Planned" for
v1.0 with no code. `docs/public/roadmap.md` had them for Q2 2026 and "native mobile renderers"
for Q3 2026; neither has shipped. Community Compose renderers exist at v0.8 and v0.9.

**Conformance and trust.** `specification/v1_0/test/` validates JSON payloads against the
schemas; it is a schema test-vector suite, not a renderer behaviour suite. A renderer
certification programme is a Q4 2026 roadmap item. There is no signing, provenance or
integrity mechanism anywhere in the protocol. Styling is deferred "entirely to the target
framework's native theme". Accessibility is a MUST that the spec admits it cannot enforce.
Licence: Apache 2.0.

### 0.2 Dogwood's contract, and which side of the boundary it lives on

**The wire is a diff stream, strictly checked, then contained.** `ChangeBatch(q, g)` with six
change kinds and an `Event` return path (`engine/dogwood-wire/.../protocol/Protocol.kt`), encoded
as positional JSON whose grammar is documented in `PositionalCodec.kt`. Four checks run on every
batch and **none of them consults the signature**:

1. Grammar and arity. `decodePositional` throws `ProtocolMismatch` and never returns a partial
   batch; an unknown change kind is refused as newer than this client.
2. Whole-batch shadow validation. `ChangeBatch.rejection` (`BatchValidation.kt`) runs the batch
   against a copy-on-write shadow tree before a node is built: dangling ids, out-of-range slot
   indices, double creates, references into a removed subtree. `HostTree.apply` calls it first.
3. Dictionary containment. Unknown widget: inert placeholder. Unknown property on a widget with
   no affordance: ignored. Unknown property on a widget that owns an affordance: the widget is
   **withheld** (architecture decision record (ADR) [ADR-031](../adrs/layer-5/ADR-031-safety-relevant-parameters.md)).
4. Value clamping from `@Range` on the surface, proven by `tools/hostile-value-drill/` against
   three values that used to kill the whole application on Android and iOS.

Everything unrecognised lands in one `SkewReport` (`engine/dogwood-host/.../Skew.kt`).

**The signature authenticates code, not data.** `SECURITY.md` §"What the sandbox is not":
Zipline is not a sandbox; the guest is trusted code verified by signature, not untrusted code
contained by isolation. A producer that emits only wire messages and no code therefore sits
*inside* a tighter boundary than the signed guest does. `GuestLimits`
(`engine/dogwood-host/src/ziplineMain/.../GuestLimits.kt`) caps the guest heap at 256 MiB and
interrupts execution at five seconds.

**Version agreement is by integer per segment.** `checkDeclaredDictionary`
(`engine/dogwood-host/.../DictionaryCheck.kt`) refuses, before `start`, a payload that declares
a segment the host lacks or a version the host is behind. Declaring fewer segments is fine. The
web Worker envelope has its own `REVISION` handshake (`engine/dogwood-web/.../WorkerProtocol.kt`).

**The lock is the definition of a component.** Each segment's `*.lock.json` (six of them, listed
by `find engine -name '*.lock.json'`) carries, per component, `name`, `localTag`, `properties`,
`propertyTypes`, `safetyRelevant`, `slots`, `events`, `eventTypes`. The curated segment
(`engine/surface/dogwood.designsystem.lock.json`, segment 1, version 15) has twenty:
`PrimaryButton`, `AsyncImage`, `Card`, `Badge`, `Divider`, `Chip`, `Price`, `StarRating`,
`SectionHeader`, `Icon`, `TextInput`, `Presence`, `ScrollArea`, `SnackbarArea`, `Dialog`,
`SheetArea`, `Menu`, `MenuItem`, `DatePickerArea`, `TimePickerArea`. `PrimaryButton` has
`label: TextValue`, `enabled: Boolean` marked safety-relevant, and one event `onClick`. Layout
primitives are segment 0 (`Text`, `Column`, `Row`, `Box`, `Spacer`, `VerticalList`,
`HorizontalList`, `Pager`, per `developer-experience.md` §1) with about thirty modifiers.

**Nothing says what a component is for.** `engine/dogwood-codegen/.../Docs.kt` states it
plainly: the reference "carries no prose about what a component is *for*, because the parser
does not read documentation comments — and a generator that invented that prose would be worse
than one that omits it." `engine/surface/dev/dogwood/surface/DesignSystemSurface.kt` declares
`PrimaryButton` with no comment at all. A model choosing between `PrimaryButton` and `Chip`
needs that sentence. **This is authored work, not a lift**, and §1.3 says who writes it.

**Text crosses as numbers, not strings.** A `TextValue` property is a literal or a recipe, a
factory id plus arguments, resolved on the host with the locale and time zone in force, because
the pinned QuickJS ships no `Intl` (`engine/dogwood-host/.../HostResolved.kt`). The factories
(`engine/dogwood-wire/.../Identifiers.kt`): `TEXT_NUMBER`, `TEXT_CURRENCY`, `TEXT_PERCENT`,
`TEXT_DATE`, `TEXT_TIME`, `TEXT_DATE_TIME`, `TEXT_RELATIVE_TIME`, plus `COLOR_TOKEN` and
`COLOR_ARGB`. Plural category is host-vendored (ADR-037). A2UI's formatting functions map onto
these almost one to one, and where they do not, the gap is named in Part 2 §2.5.

**The theme is host-owned.** `engine/dogwood-host/.../Theme.kt`: screens name colours and the
client decides what the names mean; an unknown token degrades to unspecified and is reported;
claim C1 proves a theme swap repaints with zero guest traffic. A2UI defers styling to the native
theme, so these agree.

**Network is default-deny per host.** `allowHosts` in
`engine/dogwood-host/src/jvmAndroidMain/.../PlatformServices.kt`, and a separate image policy
of the same shape (`ImagePolicy.kt`). The agent endpoint is one allowed host.

**There is no non-Zipline producer harness.** The web profile already proves the wire is
transport-independent: `WorkerProtocol.kt` says the change batch a Worker message carries "is the
same positional JSON string the mobile profile puts through Zipline's `CallChannel`, byte for
byte." But there is no wire fixture format, no replayer, no JSON-lines transcript of a session,
and host tests type wire strings by hand. `HostTree.apply(ChangeBatch)` and
`decodePositional(String)` are public, so a host can be driven from a string; that is the seam.

**The guest's host-facing services.** `engine/dogwood-protocol/.../Services.kt` declares
`DogwoodHost` and `DogwoodGuestUi`; `HostServices.kt` declares `DogwoodServices`,
`DogwoodNavigation`, `DogwoodLog`, `DogwoodClock`. The services surface is versioned as
`dogwood.services` revision 2 (`ServiceSurface.kt`). A translator running as a guest reaches the
network and navigation through these and nothing else.

**Conformance.** Eighty claims across families A to M in `plans/conformance.md`, evidence bound
in `tools/conformance/claims.tsv`, graded per client into `result-<client>-<date>.conf`, tier S
in the merge gate and tier C nightly (`.github/workflows/tier-c.yml`). **There is no family N.**
The last recorded ADR is 078.

**Tooling on hand.** `tools/reference-server/` (publish, rollout, cohorts, kill switch, module
pool), `tools/conformance/` (four client runners, aggregate, matrix write-back with a coverage
guard), `tools/skew-drill/`, `tools/hostile-value-drill/`, `tools/a11y-drill/`.

### 0.3 What this plan assumes, stated so it can be falsified

- A2UI's flat adjacency list maps onto Dogwood's tree of slots without a new subsystem. §1.1
  exists to test exactly this before anything else is built.
- A model given a JSON Schema catalogue with descriptions produces usable screens from the
  curated vocabulary. Nothing in this repository has ever put a model in the loop. §1.6 is the
  first time, and it is placed before the conformance family so the family grades what a model
  actually emits rather than what a hand-written fixture imagines.
- Google will not ship a Compose renderer that makes this redundant before it is built. It has
  slipped two quarters; the community renderers are two versions behind. If it ships, §1.4
  changes and the rest does not.

---

## Part 1 — The plan

Eight items. The first is deliberately tiny and can end the plan. Each has **what it is**, **done
means**, and **the gate**, in the pattern of the earlier plans.

### 1.1 The one-message proof

**What it is.** Hand-write one A2UI `createSurface` message using only components the curated
segment and layout primitives can draw (a `Column` of `Text`, a `TextField`, and a `Button` with a
`Text` child bound to a data model). Translate it into a `ChangeBatch` with a throwaway script,
feed the batch to `HostTree.apply` on the desktop host, then on an Android emulator, and read the
result back through `HostTree.describe()` and the accessibility tree.

**Why first.** It answers the only question that could sink the idea: whether A2UI's shape
(flat list, ids, JSON Pointer bindings, a Button whose label is a child component rather than a
property) fits Dogwood's shape (tree, integer slots, `TextValue` properties) without a new
subsystem. A day or two. The roadmap's Phase 0 had the same purpose and the same size.

**Done means.** The screen renders on desktop and Android; tapping the button produces an
`Event` whose id maps back to the A2UI component id; the throwaway script and both messages are
committed under `tools/a2ui-proof/` with the transcript. The `describe()` output is in the plan
under "What landed".

**The gate.** Send a second message that references a child id that does not exist and one that
nests a `Button` inside a `Text`. Both must be refused by the existing host checks with no
change to host code, and the refusal text recorded. If either renders, the shadow validation is
not the boundary this plan says it is, and §1.5 grows.

### 1.2 Two decisions, recorded before code

**What it is.** Two ADRs, Proposed, then Accepted when §1.1 passes.

- **ADR-079, capability is not permission.** Records the table at the top of this document as
  a decision: the dictionary is the ceiling, a catalogue is a policy over it, a host may carry
  many, and permission is enforced on the renderer per message. Names the four host-side checks
  as the enforcement point and states the one behavioural change: a message from an agent that
  fails containment is **refused and reported**, not degraded, because degradation is a courtesy
  extended to a trusted producer.
- **ADR-080, the translator is a second producer.** Records that the A2UI translator is a
  guest, why (protocol churn ships over the air; one implementation reaches four clients; the
  guest sandbox's limits apply to it), what it may not do (touch the wire format, hold per-frame
  state, reach the network except through `DogwoodServices`), and the alternative rejected
  (a native translator compiled into each host, cheaper on latency, paid for in app releases).

**Done means.** Both ADRs exist under `adrs/layer-5/`, list the documents they change, and are
in `adrs/README.md`. `specs/layer-1-authoring.md` gains a section "The second producer";
`specs/layer-4-sandbox.md` and `specs/layer-5-host.md` each gain the paragraph that says a batch
may come from a translator and what changes when it does; `high-level-tech-spec-final.md` §3's
diagram gains the agent as a source.

**The gate.** The ADRs are reviewed by the three adversarial roles in `AGENTS.md` §4, and the
findings are fixed in the text, not appended.

### 1.3 The catalogue emitter, and the sentences it needs

**What it is.** A third output from `dogwood-codegen`, beside the guest stubs and host
bindings: for a segment's lock, an A2UI catalogue definition conforming to
`catalog_definition.json`. The mapping:

| Lock field | Catalogue field |
|---|---|
| `name` | the component's key under `components` |
| `properties` and `propertyTypes` | JSON Schema properties, typed; `TextValue` becomes A2UI's `DynamicString` plus the recipe forms Part 2 §2.5 lists |
| `safetyRelevant` | `required` |
| `slots` | `child` for a single slot, `children` for a list slot, using A2UI's `ComponentId` and `ChildList` so validators can walk the tree |
| `events` | an `action` property where the event is a plain trigger; anything else omitted and listed as unmapped |
| `@Range` on the surface | `minimum` and `maximum` |
| segment `wireName` and `version` | the `catalogId`: `https://<publisher>/dogwood/<wireName>/<version>/catalog.json` |

Two catalogues are produced from it, and they are the only two this plan releases:

- **`basic`**: A2UI's own eighteen components, mapped onto Dogwood as Part 2 §2.4 shows. It
  exists so that any A2UI agent written against Google's basic catalogue works against a
  Dogwood host unchanged. It is not a Dogwood lock; it is a hand-maintained mapping file that
  the emitter resolves against three locks (segments 0, 1 and 255) and refuses to build if any
  target is missing.
- **`curated`**: the twenty design-system components, the layout primitives, and a named subset
  of modifiers. This is the catalogue the plan recommends putting in front of agents by
  default.

**The sentences.** The parser will read a documentation comment on a surface declaration and
carry it into the lock as `description`, per component and per parameter. That is a change to
`Parser.kt` and `Surface.kt` and to the lock's shape, which under `Lock.kt`'s rules is an
additive change requiring a version bump. Then twenty components and eight primitives get a
comment written by a person, in the form "what it is for, and when not to use it". `Docs.kt`'s
principle stands: the generator carries prose, it never invents it, and a component without a
comment is emitted into the catalogue without a description and reported by `check` (§1.5) as
a gap.

**Done means.** `./gradlew :dogwood-codegen:generateCatalogs` writes
`build/generated/a2ui/basic/catalog.json` and `.../curated/catalog.json`; both validate against
`catalog_definition.json` using A2UI's own `specification/v1_0/test/run_tests.py`; the twenty
curated components each carry a description; `docs/api/dogwood.designsystem.md` shows the same
sentences, because it is emitted from the same parse.

**The gate.** Delete one description and one `@Range`; the catalogue must change in exactly the
expected two places and nowhere else. Rename a component in the lock; the emitter must refuse,
because a renamed component is a new catalogue id, not an edit.

### 1.4 The translator

**What it is.** A new module `engine/dogwood-a2ui`, a Kotlin guest built and signed like any
other payload, whose only job is A2UI in, change batches out, actions and errors back. Its
responsibilities, each a unit of work with its own tests:

1. **Session.** Open the connection to the agent endpoint through `DogwoodServices`' network,
   send renderer capabilities (the catalogue ids the host reports through the pre-flight it
   already runs), read JSON Lines, apply messages in order.
2. **Tree.** Turn the adjacency list into `Create` and `ChildAdd` changes against the locked
   catalogue's tags; hold the id map; handle `updateComponents` as replace-in-place with
   `ChildRemove` and `ChildAdd`; handle a child referenced before it arrives with a placeholder
   and a pending list (A2UI's progressive rendering).
3. **Data model.** Hold the renderer-side model as a JSON tree; resolve JSON Pointer paths,
   absolute and template-relative; apply `updateDataModel`; expand `List` templates into one
   subtree per item with the item as the relative root.
4. **Text.** Emit literal strings as `TextValue` literals and A2UI's `formatNumber`,
   `formatCurrency`, `formatDate` and `pluralize` as recipes, so the number crosses and the host
   formats it. `formatString` is done in the translator.
5. **Inputs.** Bind `TextField` to the host's text-input state object with its edit counter
   (the existing bespoke subsystem, never value plus callback), `CheckBox` and `ChoicePicker` and
   `Slider` to their Material 3 bindings, and write their values into the data model on the
   host's debounced mirror, not per keystroke. Run `required`, `regex`, `length`, `numeric`,
   `email` on the translator side and surface the result as the field's error state.
6. **Actions.** On an `Event` for a bound `action`, resolve the context paths against the data
   model and send an `action` message with `name`, `surfaceId`, `sourceComponentId`,
   `timestamp` and `context`; attach the data model if `sendDataModel` was set.
7. **Errors.** Any refusal from the host's four checks, and any catalogue-policy refusal from
   §1.5, becomes an `error` message to the agent with the matching A2UI code and one sentence,
   and is logged through `DogwoodLog` with the same fields a skew report carries.
8. **Versions.** Speak v0.9.1 and v1.0 message names behind one switch keyed on the `version`
   field; the difference is mostly names and this is where a future rename is absorbed.

**What it does not do.** It never emits a change kind, tag or property that is not in the lock
it was built against; it holds no per-frame state; it does not format text; it does not choose
colours (an A2UI `variant` maps to a named token, never to a literal); it does not open Uniform Resource Locators (URLs)
itself, it asks `DogwoodNavigation`, which allows `http` and `https` only, as A2UI's
implementation guide requires.

**Done means.** The module builds for the JavaScript target, is signed and published by the
reference server like `slice-guest`, and starts on all four clients. Unit tests in the shared
source set cover each responsibility with hand-written messages. The proof from §1.1 is now a
test.

**The gate.** Each unit test is watched to fail with its responsibility disabled. The five-second
execution interrupt is exercised: a message with ten thousand components must be refused by size
before the interrupt fires, and the refusal is an `error`, not a dead guest.

### 1.5 Catalogue policy, the check, and enforcement

**What it is.** Three pieces that together turn permission into something a product team
configures.

**The policy file.** One YAML Ain't Markup Language (YAML) document per kind of surface, in the format Part 3 §3.3
specifies. Every line is a subtraction from or a constraint on a catalogue: allowed components,
modifiers, text styles and colour tokens; required properties; depth, child-count and
per-surface limits; `allowedParents` and `allowedChildren`; composites; permitted action names;
examples; a rules file. It can `extend` a published catalogue and narrow it; it can never widen
past the dictionary.

**The check.** `tools/a2ui/check.py <policy>`: every named component, property, modifier and
token exists in the lock; every composite compiles and its properties match its declaration;
every example validates against the catalogue the policy produces and against the policy's own
limits; every allowed component has a description; the output ends with the list of what the
policy leaves out of the dictionary, so the narrowing is a visible decision. Exit non-zero on
any failure. Built to run in the merge gate.

**The outputs.** From one policy, four artifacts that cannot disagree: the A2UI catalogue
definition the agent receives; a validator configuration the translator loads; a prompt pack
(the rules text and the examples, with the schema); and the conformance fixtures for §1.7. The
catalogue id includes the policy's name and version.

**Enforcement.** The translator loads the validator configuration for each catalogue id the host
advertises, and refuses any component, property, nesting or count outside it **before**
building a change, with the A2UI error code. This is the line that makes permission real: the
host can draw a `Slider`, the checkout catalogue does not allow one, and a message that asks for
one is refused. The host's own four checks remain behind it as the second line.

**Composites.** A composite is an ordinary composable in a Kotlin module, compiled into the
translator payload or a sibling payload, and exposed in the catalogue as one component with
typed properties. It is where a team's patterns live, and it ships without a host release
because it is payload code composing existing vocabulary. The policy names it by fully
qualified function name and the check confirms it exists and that its parameters are all
by-value types the catalogue can express.

**Done means.** `basic` and `curated` are re-expressed as policies and produce byte-identical
catalogues to §1.3's direct emission. A third policy, `checkout`, extends `curated`, allows
seven components, defines one composite, and is the policy the sample and the guide use.
`check` runs in the tier-S workflow.

**The gate.** For each of the check's rules, a policy that breaks it is committed under
`tools/a2ui/negative/` and the check must fail it with the rule named. Enforcement is graded
by claim N6 in §1.7 and must be watched to fail with the validator configuration removed.

### 1.6 A reference agent, and the first end-to-end run

**What it is.** A small agent under `tools/a2ui/agent/` on Google's Python SDK, talking to a
real model, serving one endpoint that the translator connects to. It takes the prompt pack from
§1.5, uses the catalogue schema as the tool parameter schema for the model's UI tool so the
sampler is constrained where the model API allows it, runs the SDK validator, feeds errors back
to the model with a retry limit of three, and streams JSON Lines. The reference server gains a
`session` route that proxies to it, so a client already pointed at `localhost:8080` for payloads
needs no second host allowed.

**The run that matters.** On each of the four clients: a person types "book a table for four at
seven", a screen appears from the model's output, the person changes the party size and taps
the button, an `action` arrives at the agent with the resolved context, and an updated screen
appears. Recorded as a transcript: every message in both directions, the model's raw output
before fixing and validation, and each client's accessibility-tree dump at the two screens.

**Done means.** The transcript is committed under `tools/a2ui/transcripts/<date>/` for all four
clients. The count of model retries per run is in the transcript and in this plan's "What
landed". The model used, its version, and the prompt pack version are named.

**The gate.** Run it ten times on one client with the same prompt. Report how many of the ten
produced a screen the validator accepted first time, how many needed a retry, and how many
failed after three. **This number is the first honest measure of whether the catalogue is small
enough**, and it is recorded whatever it is. If fewer than seven of ten pass first time, §1.5's
`curated` policy is narrowed before §1.7 grades anything.

### 1.7 Conformance family N, and the nightly

**What it is.** A new family in `plans/conformance.md` Part 2 and rows in `claims.tsv`, in the
style of the existing families. Proposed claims:

| ID | Claim | Tier |
|---|---|---|
| N1 | A2UI's own schema test vectors (`specification/v1_0/test/cases`) pass through the translator: valid cases produce a batch the host applies, invalid cases produce an `error` with the code the vector expects | S |
| N2 | Every example in a released catalogue renders and **announces** the same on all four clients: the same controls, by spoken label, in the same order | C |
| N3 | An unknown component, a dangling child id, a cycle, a binding to a missing path, and a forbidden nesting each produce an `error` with the right code and **no crash**, on all four clients | C |
| N4 | A message referencing a child that has not arrived renders a placeholder and completes when the child arrives, in either order | C |
| N5 | A tapped action arrives at the agent with its context resolved from the **current** data model after the person has edited a field, not the model's initial values | C |
| N6 | A component the host can draw but the locked catalogue does not allow is refused with `VALIDATION_FAILED`, and the refusal names the catalogue id | C |
| N7 | A message of ten thousand components is refused by size before the guest's execution interrupt fires, and the surface that was showing stays showing | C |
| N8 | The translator built against catalogue version *n* and a host advertising *n+1* start, and the host's extra components are unreachable from the *n* catalogue; the reverse refuses before `start`, naming the catalogue | C |

Each gets a test class per client, a `CONF RESULT` line, and a row in `claims.tsv`. The
fixtures are the ones §1.5 generates from the catalogue's examples plus hand-written hostile
messages under `tools/conformance/a2ui/`.

**Done means.** All eight rows are in Part 3's matrix with a result on every client the claim
names, produced by the nightly, composed through `from_tests.py` as the matrix guard requires.
`tier-c.yml` runs the translator payload and the reference agent in the device jobs.

**The gate.** Every claim watched to fail: N2 with one client's binding for one component
removed; N3 with the shadow validation bypassed; N5 with the data model read before the edit;
N6 with the validator configuration removed; N7 with the size check removed. The nightly must be
green three consecutive nights before the family is called closed, because the last family
needed eight dispatches to get there and each failure was real.

### 1.8 The evaluation harness, last

**What it is.** `tools/a2ui/eval/`: a set of prompts, run through the reference agent, each
output through the gates, each accepted screen rendered on desktop and screenshotted, each
screenshot and its message judged against a rubric the adopter writes. The harness ships with
ten prompts and a rubric for `curated` that grades only what a rubric can: one primary action
per screen, every input labelled, no more than the policy's depth, text styles from the allowed
set. It reports pass rates per rule and per prompt.

**Why last.** It needs everything above to exist, and its bar belongs to each adopter. Dogwood
ships the harness and one rubric as an example. It does not ship the judgement.

**Done means.** The harness runs from one command against the `checkout` policy and writes a
Markdown report with the ten screenshots and the per-rule table. The report for `curated` is
committed once, dated, as the baseline.

**The gate.** Change one rubric rule to its opposite; the pass rate for that rule must invert.

### 1.9 Documentation

`docs/a2ui.md`, which is Part 3 of this document promoted once §1.5 and §1.6 have landed, with
every "will" changed to "does" and every path checked by the link check. A row in
`docs/README.md`'s front door: "Putting a model in front of Dogwood". A section in
`docs/operating.md`: how to read an A2UI `error` in the log, and what the retry count in the
agent means for the person on call. `docs/security.md`'s threat table gains a row for the agent
endpoint, answered by `allowHosts` and the translator's refusal policy.

### Order, gates, and what could go wrong

```
1.1 proof ──▶ 1.2 ADRs ──▶ 1.3 emitter ──▶ 1.4 translator ──▶ 1.5 policy ──▶ 1.6 agent + run ──▶ 1.7 family N ──▶ 1.8 eval ──▶ 1.9 docs
     │                                                                            │
     └── can end the plan                                            narrows 1.5 if the ten-run number is poor
```

| Step | Sequencing guidance, one engineer | What could go wrong, and what then |
|---|---|---|
| 1.1 | 1–2 days | The Button-with-child shape does not flatten onto `label: TextValue`. Then `basic` maps `Button` to a `Box` with `clickable`, and `curated` keeps `PrimaryButton`. |
| 1.2 | 2 days | Review finds the guest is the wrong home for the translator. Then ADR-080 records the native alternative and §1.4's module moves into `dogwood-host`; the rest is unchanged. |
| 1.3 | 1 week, of which the twenty-eight sentences are two days of careful writing | The lock's version bump for `description` invalidates every committed sample payload. It should; that is what the lock is for. Re-sign the samples in the same change. |
| 1.4 | 3 weeks, the largest item | `List` templates with relative bindings interact badly with the lazy-list windowing the host already does. Then templates expand eagerly in the first release and the limit on list length is in the policy. |
| 1.5 | 2 weeks | The YAML grows a second way to say something. The format has one way per concept and the check refuses unknown keys. |
| 1.6 | 1 week plus device time | The model ignores the catalogue and emits Material names it has seen elsewhere. That is the number the ten-run gate exists to surface, and the answer is a smaller catalogue and better examples, not a looser validator. |
| 1.7 | 2 weeks plus nights | Family M needed eight nightly dispatches. Assume the same. |
| 1.8 | 1 week | The rubric grades taste and becomes an argument. Keep it to the rules a person can point at. |

These are a floor, not a midpoint, for the same reason the roadmap's Phase 3 estimate was.

### What this plan does not do

- **It does not expose the Material 3 tier to agents.** The tier is in the dictionary and a
  policy can allow it. This plan releases `basic` and `curated` only, and says why in Part 2 §2.7.
- **It does not sign agent output.** The trust boundary is the endpoint, controlled by
  `allowHosts`; the translator is signed; the messages are data validated on arrival.
- **It does not add a native renderer outside the host.** The host is the renderer.
- **It does not change the wire, the lock's rules, or how compiled guests work.** A developer's
  payload keeps the whole dictionary.
- **It does not implement A2UI's function-call messages** (`callRendererFunction` and its
  three siblings). They are v1.0 additions with no renderer support anywhere yet, and nothing
  in §1.6's scenario needs them. They are carried forward, not closed.
- **It does not decide the publisher's domain for catalogue ids.** That is an open decision in
  the same sense as the Maven coordinates, and it goes in `OPEN-DECISIONS.md` when §1.3 starts.

---

## Part 2 — The execution model

What happens, in order, when a person uses a Dogwood application whose surface is driven by an
agent. Nothing here is built; it is what the plan builds.

### 2.1 The parties

- **The person**, using the application.
- **The host**: the application, with Dogwood's host library, its dictionary, and the four
  checks. It draws everything. It advertises which catalogues it can serve.
- **The translator**: a signed Dogwood guest running in the sandbox, loaded like any payload.
  It speaks A2UI outward and the wire inward.
- **The agent**: a server the application is allowed to talk to. It holds the conversation,
  runs tools, and calls the model.
- **The model**: emits A2UI messages as the arguments of a tool call, given the catalogue.
- **The catalogue**: a JSON Schema the agent was given and the host advertised, produced from a
  policy over the dictionary.

### 2.2 One turn, end to end

```mermaid
sequenceDiagram
    participant P as Person
    participant H as Host (Dogwood, native)
    participant T as Translator (signed guest)
    participant A as Agent (server)
    participant M as Model

    P->>H: types "book a table for four at seven"
    H->>T: start(surface, catalogue ids the host can serve)
    T->>A: message + rendererCapabilities{supportedCatalogIds}
    A->>A: lock one catalogue for this surface
    A->>M: prompt: task + catalogue schema + rules + examples
    M-->>A: tool call: createSurface{...}  (gate 1: constrained to the schema)
    A->>A: validate (gate 2); on failure, error back to M, retry ≤ 3
    A-->>T: JSON Lines: createSurface, updateComponents...
    T->>T: policy check (gate 3a): allowed? nested legally? within limits?
    T->>T: build ChangeBatch against the locked catalogue's tags
    T->>H: sendChanges(batch)
    H->>H: grammar, shadow validation, containment, clamps (gate 3b)
    H->>P: native Compose renders; theme is the host's
    P->>H: edits party size, taps Book
    H->>T: sendEvent(id, tag, args)
    T->>T: resolve action.context against the data model
    T-->>A: action{name, surfaceId, sourceComponentId, context}
    A->>A: book the table
    A->>M: next prompt, with the action
    M-->>A: updateDataModel / updateComponents
    A-->>T: JSON Lines
    T->>H: sendChanges(diff)
    H->>P: updated screen
```

**Person.** Types and taps. Sees native widgets, the application's own theme, and its own
accessibility behaviour, because the host draws everything.

**Host.** Loads the translator as a signed payload, exactly as it loads any guest, with the
pre-flight dictionary check. Reports the catalogue ids it can serve, derived from the segments
it has at the versions it has. Applies batches through the four checks. Routes events back.
Never sees A2UI.

**Translator.** The only party that speaks both languages. Owns the session, the id map, and
the renderer-side data model. Refuses first, on policy; builds second; sends third. Turns every
refusal, its own or the host's, into an A2UI `error`.

**Agent.** Owns the conversation and the business logic. Runs gate 2 and the retry loop. Locks
the catalogue. Never sees the wire.

**Model.** Sees the catalogue schema and the prompt pack. Emits messages. Is told when it was
wrong, in the next prompt.

### 2.3 The gates, and what each actually guarantees

| Gate | Where | Blocks | Catches | Cannot catch |
|---|---|---|---|---|
| 1 | model API, schema-constrained tool call | generation | unknown component or property names, wrong types, missing required fields | dangling ids, cycles, nesting, meaning |
| 2 | agent SDK validator, then retry | sending | gate 1 plus id resolution, cycles, `allowedParents` and `allowedChildren` | meaning |
| 3a | translator, policy configuration | building a change | gate 2 plus the policy's limits, allowed actions, catalogue lock | meaning |
| 3b | host, the four existing checks | drawing | everything a compiled guest could get wrong: grammar, batch consistency, unknown tags, out-of-range values | meaning |
| none | | pixels | | whether the screen is good |

"Meaning" is whether the model chose the right component. No gate checks it. It is bounded by
the size and shape of the catalogue, which is why §1.5 does more for quality than any validator,
and measured by §1.6's ten-run number and §1.8's rubric.

Gates 3a and 3b are both on the device and both refuse rather than degrade for an agent. The
difference is what they know: 3a knows the policy, 3b knows the dictionary. A message can pass
3a and fail 3b only if the translator has a bug, and that is what N3 grades.

### 2.4 Component mapping, `basic` to Dogwood

A2UI's basic catalogue has eighteen components. Sixteen map; two do not.

| A2UI | Dogwood binding | Segment | Notes |
|---|---|---|---|
| Text | `Text` | 0 | `variant` → a named text style token |
| Image | `AsyncImage` | 1 | `fit` has no target today; omitted from the schema and reported by `check` |
| Icon | `Icon` | 1 | |
| Video | none | | not in `basic` on Dogwood; the catalogue says so |
| AudioPlayer | none | | as above |
| Row | `Row` | 0 | `justify`, `align` → arrangement and alignment |
| Column | `Column` | 0 | as above |
| List | `VerticalList` / `HorizontalList` | 0 | `direction` selects; template children expand per item |
| Card | `Card` | 1 | one `content` slot |
| Tabs | Material 3 tabs | 255 | `basic` is the only catalogue in this plan that reaches segment 255, for this and the four below |
| Modal | `Dialog` | 1 | `trigger` becomes the child that opens it; second-release shape, carried forward if §1.1 shows it needs a subsystem |
| Divider | `Divider` | 1 | |
| Button | `PrimaryButton` | 1 | a `Text` child flattens to `label`; any other child refused in `basic` |
| TextField | `TextInput` | 1 | host-owned edit state; `validationRegexp` runs translator-side |
| CheckBox | `Checkbox` | 255 | |
| ChoicePicker | segmented button or radio group | 255 | `displayStyle` selects |
| Slider | `Slider` | 255 | |
| DateTimeInput | `DatePickerArea` / `TimePickerArea` | 1 | `enableDate`, `enableTime` select |

`curated` is not a mapping; it is the segment 0 and 1 vocabulary as it stands, with descriptions.

### 2.5 Functions

| A2UI function | Where it runs | How |
|---|---|---|
| `formatNumber`, `formatCurrency` | host | `TEXT_NUMBER`, `TEXT_CURRENCY` recipes; the number crosses |
| `formatDate` | host | `TEXT_DATE`, `TEXT_TIME`, `TEXT_DATE_TIME` by the requested parts |
| `pluralize` | host | the plural recipe, host-vendored rules |
| `formatString` | translator | string interpolation over the data model |
| `required`, `regex`, `length`, `numeric`, `email` | translator | on the debounced mirror of the field; result is the field's error state |
| `and`, `or`, `not` | translator | over the above |
| `openUrl` | host, through `DogwoodNavigation` | `http` and `https` only |

### 2.6 State, lifetime and failure

- **The data model lives in the translator**, which lives in the guest. A guest generation is
  replaced on payload update and lost on process death (the open item in the technical
  specification §7, item 12). For an agent surface that means: on process death the surface is
  gone and the next turn recreates it. The agent holds the conversation, so nothing the person
  said is lost; what they typed and did not submit is. This is stated in the guide.
- **Surface lifetime** is the agent's to end with `deleteSurface`. A translator that loses its
  connection keeps the last surface drawn, disables its actions, and reports through
  `DogwoodLog`; it does not blank the screen.
- **An invalid message** produces an `error` to the agent and leaves the screen as it was. The
  agent decides whether to retry the model. The translator never retries on its own.
- **A slow model** is the agent's problem; the translator shows whatever partial surface has
  arrived, which is what progressive rendering is for.
- **Limits.** The policy's depth and count limits are checked before a change is built. The
  guest's 256 MiB heap and five-second interrupt remain behind them, as the last line.

### 2.7 Why `curated` first, and Material 3 not at all in this release

The dictionary has two hundred and sixty library composables, one hundred and ten of them bound.
A model given all of them will use them, and the screens will be legal and inconsistent, because
the vocabulary offers six ways to make a button. The curated segment offers one. Every design
rule a team cares about is easier to encode as "the catalogue does not contain the alternative"
than as a validator rule about when the alternative is acceptable. A team that wants the wider
tier can allow it in a policy; the check will show them what they let in.

### 2.8 Trust, stated once

| Thing | Trusted because | Checked by |
|---|---|---|
| the translator | signed with the payload key, delivered like any guest | Ed25519, dictionary pre-flight |
| the catalogue the host advertises | derived from the host's own locks | `check` at build time |
| the agent endpoint | on the host's allow list | `allowHosts`, default-deny |
| a message from the agent | **nothing** | gates 3a and 3b, every message |
| the model | **nothing** | gates 1, 2, 3a, 3b |

---

## Part 3 — Guide for use

**This part describes the end state of the plan.** Nothing in it exists yet. When it does, this
part becomes `docs/a2ui.md` and the front door links to it. It is written for the product team
that owns a surface, not for the engine.

### 3.1 What you are choosing

You have an application with Dogwood in it. You want a model to build some of its screens at
runtime. You are about to decide three things: **which catalogue** the model may use, **what
rules** narrow it for your surface, and **what the model is told**. Everything else is
mechanism, and the mechanism refuses anything outside what you decided.

Two things are true before you start:

- The model can never make the host draw something the host cannot draw. The dictionary is the
  ceiling and it is fixed by your application build.
- The model can never make the host draw something your policy does not allow, even if the host
  could draw it. That is enforced on the device, per message, not by trusting the agent.

### 3.2 Step by step

1. **Pick a starting catalogue.** `curated` if you are building a surface for your own product.
   `basic` if you must work with an existing A2UI agent written against Google's catalogue. Do
   not start from the Material 3 tier.
2. **Write a policy** for the kind of surface, in `surfaces/<name>.a2ui.yaml`. §3.3 is the
   reference. Start by allowing fewer components than you think you need.
3. **Write your composites**, if you have patterns the model should not be allowed to get wrong:
   an order line, a price block, an address card. Each is an ordinary composable in your payload
   module, named in the policy.
4. **Run the check**: `tools/a2ui/check.py surfaces/<name>.a2ui.yaml`. Fix what it names. Read
   the list of what your policy leaves out; that list is your decision, written down.
5. **Build the outputs**: `./gradlew :dogwood-codegen:generateCatalogs -Ppolicy=surfaces/<name>.a2ui.yaml`.
   You get the catalogue definition, the validator configuration, the prompt pack and the
   conformance fixtures under `build/generated/a2ui/<name>/`.
6. **Advertise it in the host.** Register the catalogue id in your application's Dogwood
   configuration beside the segments it already registers. The host will advertise it to the
   translator, and the translator will load its validator configuration.
7. **Allow the agent endpoint**: add it to `allowHosts`. Nothing else on the network changes.
8. **Build the agent.** Give the model the prompt pack as its system prompt and the catalogue
   definition as the parameter schema of its UI tool. Use the reference agent under
   `tools/a2ui/agent/` as the starting point. Keep the retry limit.
9. **Run the conformance fixtures** for your policy on the clients you ship:
   `tools/conformance/run-<client>.sh --a2ui surfaces/<name>.a2ui.yaml`. Every example in your
   policy must render and announce the same on each.
10. **Run the evaluation** with your prompts and your rubric:
    `tools/a2ui/eval/run.py --policy surfaces/<name>.a2ui.yaml --prompts prompts/ --rubric rubric.yaml`.
    Read the screenshots. Narrow the policy if you do not like what you see. Repeat.

### 3.3 The policy file, field by field

```yaml
catalog: acme/checkout            # your name for it; becomes part of the catalogue id
version: 3                        # bump on any change; the id changes with it
extends: dogwood/curated@15       # a published catalogue; you may only narrow it

allow:
  components: [Column, Row, Text, PrimaryButton, TextInput, Card, Divider]
  modifiers:  [padding, fillMaxWidth, weight]
  text.style: [bodyMedium, titleMedium, headlineSmall]
  colors:     tokens-only         # named theme tokens only; literals refused

require:                          # per component, properties the model must always supply
  PrimaryButton: [enabled, action]
  TextInput: [label]

limits:
  depth: 6                        # nesting
  children: 12                    # per container
  components: 60                  # per surface
  PrimaryButton.perSurface: 2

structure:                        # A2UI allowedParents / allowedChildren, generated from these
  PrimaryButton.allowedParents: [Row, Column]
  Card.allowedChildren: [Column]

composites:
  OrderLine: com.acme.checkout.OrderLine      # a composable in your payload; exposed as one component
  PriceBlock: com.acme.checkout.PriceBlock

actions: [confirm_order, apply_promo, cancel] # the only action names the model may emit

examples: examples/checkout/*.json            # valid surfaces; become fixtures and prompt material
rules: rules.md                               # prose the model reads; generated schema is appended
```

| Field | Meaning | Checked how |
|---|---|---|
| `catalog`, `version` | the id | a change to anything below without a bump fails the check |
| `extends` | the parent catalogue | must be published and at a version the host has |
| `allow.*` | the vocabulary | every name must exist in the parent; the check lists what is excluded |
| `require` | required properties, on top of the lock's safety-relevant ones | must name real properties |
| `limits` | counts the translator enforces before building | integers |
| `structure` | nesting rules | must name allowed components |
| `composites` | pattern components | must compile; parameters must be by-value |
| `actions` | the closed set of action names | the translator refuses others |
| `examples` | valid surfaces | each must validate against the produced catalogue and the limits |
| `rules` | prose for the model | must exist; the check does not read it |

There is one way to say each thing. The check refuses unknown keys.

### 3.4 What the model sees

For the policy above, the catalogue definition the model receives contains, for `PrimaryButton`,
this entry, generated from the lock and the surface's documentation comment:

```json
"PrimaryButton": {
  "description": "The single filled button for a screen's primary action. Use Chip for a choice and MenuItem for a list of actions.",
  "type": "object",
  "properties": {
    "component": { "const": "PrimaryButton" },
    "id": { "$ref": "common_types.json#/$defs/ComponentId" },
    "label": { "$ref": "common_types.json#/$defs/DynamicString", "description": "What the button says. Keep it to a verb." },
    "enabled": { "type": "boolean", "description": "Whether it can be pressed. Safety-relevant: always supply it." },
    "action": { "$ref": "common_types.json#/$defs/Action" }
  },
  "required": ["component", "id", "label", "enabled", "action"],
  "allowedParents": ["Row", "Column"]
}
```

The model never sees the lock, the policy, or the host's Kotlin. It sees this, the rules file,
and the examples.

### 3.5 What to expect on the device

- A screen appears progressively as the model writes; nothing shows until the root arrives.
- Typing does not go to the agent. Only an action does, with the field values resolved at that
  moment.
- An invalid message leaves the screen as it was. The agent sees an `error` naming the rule.
  Your log sees the same line through `DogwoodLog`.
- If the process dies, the surface is gone and unsaved input with it; the next turn recreates
  it from the conversation the agent holds.
- The theme is your application's. The model cannot choose colours; it can name tokens you
  allowed.

### 3.6 Reading a refusal

A refusal in the log looks like a skew report with two more fields:

```
a2ui refused surface=booking catalog=acme/checkout@3 code=VALIDATION_FAILED
  component=Slider id=size reason="not in catalogue" retries=1
```

`component` and `reason` tell you what the model asked for. `retries` tells you how many times
the agent has already sent this surface back to the model. A rising retry count on one rule is
the signal that the rule is fighting the model and the catalogue, the examples, or the rules
file should say something clearer. It is not a signal to loosen the rule.

### 3.7 What the check will refuse, and why

| You did | The check says | Because |
|---|---|---|
| allowed a component not in the parent | names it | a policy narrows; it cannot widen |
| changed a limit without bumping `version` | names the field | the id must change when the contract does |
| named a composite whose parameter is an object | names the parameter | the catalogue can only express by-value types |
| left an allowed component without a description | names it | the model cannot choose what it cannot read about |
| wrote an example that exceeds `limits.depth` | names the example and the depth | examples become fixtures and prompt material; a wrong one teaches the model wrong |

### 3.8 Versioning, plainly

Your catalogue id includes your version. Adding a component or a property is a new version and
a new id, and the host can advertise both old and new while agents migrate. Removing or renaming
anything is also a new id, never an edit, so an agent locked to the old id keeps working until
you stop advertising it. This is A2UI's rule and Dogwood's lock rule, and they agree.

---

## What landed

Nothing yet. This section is filled in as items close, in the pattern of the earlier plans:
every run, every wrong prediction, every number from a gate, and every regression the work
introduced, dated.
