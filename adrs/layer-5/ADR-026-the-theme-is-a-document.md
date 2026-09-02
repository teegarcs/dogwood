# ADR-026: The Theme Is a Document

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-025](ADR-025-where-a-colour-decision-lives.md) settled who decides an environment-dependent
value. This record settles the question that followed immediately: **how is a whole theme
managed?**

The split that answers it: token *names* are the contract, and that contract is fine — small,
curated, versioned per design-system segment. Token *values* were the problem. `Palette.Light` was
hex codes in Kotlin source, so changing the brand blue, theming a tenant, or trying a seasonal look
meant an application release — the one place this architecture was not keeping its own promise.
Everything downstream of the values was already built for them to change: the palette local is
dynamic, the evaluator's memo is keyed on palette identity, and the components read `palette()` at
draw. Only the values were pinned.

## 2. Decision

### 2.1 The theme is data: one document, one seam, any pipe

A JSON document maps names to values — both palettes, plus per-style typography overrides:

```json
{ "name": "ocean",
  "light": { "primary": "#006494", "brandGold": "#FFD700" },
  "dark":  { "primary": "#64B5DE" },
  "typography": { "titleLarge": { "sizeSp": 24, "weight": 700 } } }
```

The seam is `DogwoodEnvironment(theme = …)`: hand it a `Theme`, however the bytes arrived. Three
routes, deliberately not a fourth:

- **Route A — baked in.** `Theme.Default` is the compiled-in look; every other route degrades to
  it. This is day one, and it is all a product without remote theming needs.
- **Route B — the product's own configuration channel.** Remote config, a `/config` endpoint, a
  flag system: `Theme.fromJson(bytes, fallback)` is four lines in the host application. This is
  the expected production path, because that channel already has the rollout, targeting and
  rollback machinery a brand change deserves, and rebuilding any of it here would be worse.
- **Route C — the payload origin.** `ThemeStore` fetches `theme.json` from the same server the
  payloads come from, keeps the last good copy on disk, and never lets the network decide the
  first frame: cached at startup, fetch in the background, swap live when it lands.
- **Never the payload itself.** A theme is client-wide — every experience and the native chrome
  around them must agree — so screens name tokens and the client decides what the names mean. A
  payload wanting out for one value has [ADR-025](ADR-025-where-a-colour-decision-lives.md)'s
  literal path, which is opting out per value, not redefining the theme.

### 2.2 A bad document degrades field by field

A document that misspells one colour keeps its other forty: the broken field falls back to the
default's value and is reported, the good fields land. Only a document that is not JSON at all is
rejected wholesale — and `ThemeStore` will not let one replace a good cached copy. Wrong-looking
beats crashed; silently wrong beats neither.

### 2.3 The vocabulary is open where the class is not

`Palette` keeps its thirteen typed properties, because the component implementations read
`palette().primary` and should. But a document may define names the class has no property for —
`brandGold`, a chart ramp — and they land in an `extras` map resolved by `token(name)` exactly as
the named slots are. The core names are typed; the vocabulary is not capped by the type.

### 2.4 What a document may not change

**Spacing and corner radii**, in v1: colours and type are what rebrands actually change, and
geometry changes want layout review — holding them back keeps a bad document's blast radius at
"looks wrong" rather than "overlaps". **Font files**, ever, by this route: a document changes
sizes and weights; shipping a typeface is an application release, as
[ADR-017](ADR-017-resources-and-assets.md) already records.

### 2.5 Unsigned, and the asymmetry is reasoned

Payloads are Ed25519-signed because they are code — a tampered payload runs in the process. A
theme is data: the worst a tampered document does is make the application ugly, and transport to
the origin is already authenticated by Hypertext Transfer Protocol Secure (HTTPS). A product
wanting parity wraps the fetch and verifies a detached signature before handing bytes to the seam
— which takes bytes, not trust decisions.

## 3. Rationale & Research

**Why this was cheap: the architecture had already paid for it.** Making the palette a *value* was
[ADR-012](ADR-012-host-environment-subsystem.md)'s dark-mode work; keying the colour memo on
palette identity was the same record; typing `tint` as a `Color` was
[ADR-021](ADR-021-host-resolved-values.md). This record adds a parser, a seam parameter, and a
disk-cached fetch — the swap mechanics were all in place.

**Design-system components are what make a whole-theme swap possible at all.** The theme is
applied in exactly one place — the component implementations and the token resolver, both host-
side — so one document restyles every screen, including payloads shipped months ago. A world of
hand-picked literals everywhere has no lever to pull. The ADR-025 escape hatch is therefore also a
definition: a literal is a value that has opted out of theming, which is what owning a decision
means.

### Verified

Seven parse tests: a document restyles what it names and nothing else; names beyond the core
thirteen resolve; a bad colour falls back by field while its neighbours land; garbage falls back
wholesale; exactly two colour forms are accepted (shorthand is a guess, and this parser does not
guess); typography overrides touch only what they name. One swap test: a **generated component
whose payload never changed** resolves the built-in brand, the theme state swaps to a parsed
document, and it resolves the document's brand — there is no channel on which the change could
even reach the guest.

On the emulator: tapping "sunset" refetches the document and the running screen repaints —
flight icon, filter chips, accent bars, buttons — with the guest reload count unmoved at one.
Force-stopping and relaunching comes up in sunset **immediately**, from the disk cache, before any
fetch.

## 4. Unstated Assumptions

- **Two brands switching is N brands switching**; nothing here is dual-specific. Per-tenant theming
  is Route B with a tenant-keyed fetch.
- **Dark mode stays a pair within one document**, chosen by the same `darkMode` the environment
  already carries. A third mode is a document schema addition.
- **The cached document is trusted as much as the fetched one.** It sits in the application's
  private cache directory; an attacker who can write there has already won.
- **`ThemeStore` polls nothing.** Refresh runs when the host asks — at launch, on a push, on a
  settings change. A product wanting continuous rollout drives it from Route B's machinery instead.
- **Typography overrides assume the base is Material's scale or a host-supplied one**; a document
  cannot introduce a new style *name*, only restyle the ten that exist. New names are a dictionary
  concern, not a theme concern.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the host-resolved values section gains
  the document, the routes, and the payload prohibition.
- [`roadmap.md`](../../roadmap.md) — the resources row.
