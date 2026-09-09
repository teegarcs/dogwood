# Raising the grade

The instrument is [`tools/framework-grade/`](../tools/framework-grade/) — rubric v1, a fresh-agent
prompt, and a ledger whose baseline (2026-09-07, commit `39fb9c2`) reads **2.20/4.3 (C+)**. This
plan is ordered by what actually moves that number, which required doing the arithmetic first,
because the arithmetic carries the plan's most important conclusion:

**The score is owner-gated more than engineering-gated.** Every engineering item below, completed,
moves ~2.20 → ~2.39 — still a C+ — because the rubric (correctly) caps what artifact quality can
earn: adoption is capped at D while artifacts resolve from `mavenLocal()` alone, production
readiness is capped near C while hosting and keys are placeholders, performance is capped at C+
until a budget is graded on representative hardware, and maturity (20% of the total) is an F that
**cannot be engineered at all** — it moves with time, a public home, published artifacts, adopters,
and a second maintainer. The owner unlocks in Track O are worth roughly twice the whole of Track E:
publishing alone is ~+0.17, real hosting/keys ~+0.08, and maturity moving even to a D is +0.20.
Engineering's job here is to make each owner unlock a one-decision act with everything behind it
already built.

## Track E — engineering, ordered by leverage per effort

| # | Item | Moves | What done looks like |
|---|---|---|---|
| E1 | **Web release guard** (audit A3 remainder) | Prod, Coverage | `WebDelivery` gets release-version bookkeeping over `BrowserFileSystem`; the guarded path is the default on the fourth client too; graded as `H*` cells in the web column |
| E2 | **iOS navigation service in the sample** | Coverage | the one host that wires no navigation wires one; `J2`/`J4` graded on iOS by the existing drills |
| E3 | **Reference server** — the operational back half as a runnable reference | Prod | one small server in this repo: publish endpoint (build+sign+upload as one step), manifest hosting with the correct cache split (immutable payloads, uncacheable manifest), `Content-Encoding: br`, cohort-staged rollout against `InstallCohort`'s buckets, and resume-previous (serving a prior manifest again). `docs/operating.md` §5 stops describing and starts pointing; the standalone check serves Umbra through it |
| E4 | **iOS embedding artifact** (audit B4) | Adoption, Coverage | an XCFramework target a real Xcode project imports, with the walkthrough in getting-started; adoption stays rubric-capped at D until Track O publishes, but the cap's *other* jaw opens |
| E5 | **Cross-version drill** (audit A6 remainder) | Prod | commit today's built-and-signed payload as a frozen fixture; a conformance row loads it under every future engine — the over-the-air pairing exercised by nothing today |
| E6 | ~~The 22.1 ms GC outlier~~ ◐ | Perf | **Answerable rather than answered, and the knob is exposed.** `PauseWatcher` (ADR-013) attributes pauses through the public API, and on both hosts available the worst pause is *not* a collection; neither reproduces the sample. `GuestLimits.gcThresholdBytes` now exposes the one knob that shapes pause length — deliberately defaulted to the runtime's own number, because tuning against hardware that cannot reproduce the problem is tuning against noise. One `--es experiment pauses` run on a device that shows the tail settles it; that device is the owner's (§4) |
| E7 | ~~androidx import migration script~~ ✅ | Authoring | `tools/import-migrator` rewrites what maps one-for-one, keeps the runtime imports that are genuinely identical, and turns each real difference into a `// MIGRATE:` line naming the replacement — never an import pointing at a symbol that does not exist, which the first version produced |
| E8 | ~~Catalogue build-out~~ ◐ | Flexibility | `Dialog` and `SheetArea` landed, the latter proving the **fifth holder shape** at exactly ADR-043's advertised cost (a table entry, a holder, a mirror — no protocol change), and the former proving the machinery knows when *not* to be used. Menu, pager and pickers remain: they are first-adopter work that lifts the shipping floor, and the catalogue is 16 against a product's fifty to two hundred |

### Where Track E landed, 2026-09-08

All seven items done or honestly partial, each verified by running it. The conformance gate at the
end of it: **android 54, desktop 39, iOS 56, web 53 — PASS** (from 52/35/53/50 at the baseline).

Three items turned out to be smaller than the plan thought, and the reason is the same each time —
**the capability existed and was unreachable, undocumented, or ungraded**: the inner loop (E2/B2)
was already built, the collection knob (E6) needed exposing rather than tuning, and the iOS
navigation gap (E2) was a sample wiring one line short. Two were larger: the reference server (E3)
and the iOS framework (E4) were genuinely absent.

What each item found by being run, rather than reasoned about:

- **E1** the web guard needed a release *identity* the sidecar never carried, and its kill switch is
  weaker than mobile's because the sidecar is unsigned — recorded in the manifest field itself.
- **E2** iOS recorded skew and **displayed none**, so containment worked and was invisible.
- **E3** the first end-to-end proof loaded from the development task instead of the reference
  server and looked exactly like success; the second served only the manifest, because Zipline's
  cache was warm.
- **E4** an omitted `export(...)` produces a framework that builds and a header whose factory takes
  types the consumer cannot name — which is what the check asserts.
- **E5** a rebuild cannot test the over-the-air gap; only a frozen artifact can be old.
- **E7** the first rewriter pointed imports at symbols that do not exist, making the compiler blame
  a package for an API difference.
- **E8** the dictionary lock refused both new components until the segment version was raised, and
  `HolderTest`'s example of an *unregistered* holder stopped being unregistered the same day.

**The successor:** the engineering that remains after Track E — the catalogue, the safety
asymmetries between clients, and the matrix cells still reading `—` — is planned in
[`plans/engineering-backlog.md`](engineering-backlog.md), with the honest note that almost none of
it moves the score: run 2 confirmed the caps.

## Track O — owner unlocks, restated from [`DECISIONS-FOR-THE-OWNER.md`](../DECISIONS-FOR-THE-OWNER.md) with their score price tags

| Owner item | Unlocks | Worth |
|---|---|---|
| §5 publish coordinates | adoption cap lifts (D+ → B reachable with E4 done) | ~+0.17 |
| §6 keys + hosting (E3 makes it a deploy, not a build) | production B reachable | ~+0.08 |
| §1 an enforceable merge gate | maintainability A | ~+0.02 |
| §2 the Apple ruling | de-risks the entire iOS leg; graded inside prod/coverage | risk, not points |
| §4 representative low-end hardware | performance cap lifts | up to +0.10 |
| a public home, a second maintainer, time | **maturity — the single largest lever on the card** | +0.20 per letter step |

## Cadence

Re-run the instrument (fresh agent, `tools/framework-grade/prompt.md`, current date) after Track E
lands, and after any Track O unlock; every run appends to the ledger with its commit. The rubric
does not move.
