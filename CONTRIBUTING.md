# Contributing to Project Dogwood

This repository is a specification. The deliverable is prose, diagrams, and decision
records — so the review bar is about **technical honesty**, not code style.

Read [`AGENTS.md`](AGENTS.md) first. It is the normative document; this file is the
practical workflow around it.

## The Non-Negotiables

These come from `AGENTS.md` and apply to every change:

1. **Zero assumptions.** Do not guess an Application Programming Interface (API) surface or
   assert technical feasibility from memory. Read the source, link the GitHub repository,
   and cite the specific file. If you cannot prove it, mark it as an open assumption
   instead of stating it as fact.
2. **Spell out every acronym** on use, alongside the acronym — Server-Driven User Interface
   (SDUI), WebAssembly (Wasm), Foreign Function Interface (FFI). Every sentence should read
   without prior context.
3. **Depth and clarity together.** The plans must be deep enough to implement from, and
   clear enough for an engineer with no C or WebAssembly (Wasm) background to follow.
4. **Diagrams as code.** Architectural visuals are Mermaid.js, never images. Every layer
   document needs a diagram, and every node in that diagram must be defined in the
   surrounding text.

## Workflow

1. **Branch** off `main` — for example `spec/layer-4-sandbox` or `adr/layer-3-signing`.
2. **Write**, following the layer template in `AGENTS.md` §5 for a new layer document.
3. **Review adversarially.** No layer specification is complete until 2–3 adversarial
   reviewers have audited it in these roles:
   - **Performance/Memory** — memory leaks, zero-copy pointer risks, thread blocking,
     garbage collection bottlenecks.
   - **Integration/Boundaries** — the Foreign Function Interface (FFI) boundary, data
     marshalling, API mismatches between layers.
   - **Unknown Assumptions** — statements presented as fact that need proof or a link.
4. **Fix, do not append.** Review findings must be resolved by editing the document text.
   Do not paste a findings list at the bottom and call it addressed.
5. **Open a pull request** describing what changed and which reviews were run.

## Architecture Decision Records (ADRs)

Now that the first drafts exist, every technical decision, pivot, or resolved assumption
requires an ADR in `adrs/layer-X/`. Use the template in `AGENTS.md` §3.

An ADR is only valid if its **Updated Documents** section lists every specification it
changed. A decision that touches no document either is not a decision yet, or the documents
have not been updated — fix whichever is true.

## Licensing of Contributions

This project is licensed under the [Apache License 2.0](LICENSE). Under Section 5 of that
license, any contribution you intentionally submit for inclusion is licensed under the same
terms, without any additional conditions — including the express patent grant in Section 3.
If you need different terms, say so in the pull request before it is merged.

## Changing the Rules Themselves

`AGENTS.md` governs everything here, so changing it is an architectural decision in its own
right. Propose it as an ADR first.
