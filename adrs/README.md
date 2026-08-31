# Architecture Decision Records (ADRs)

This directory records the technical decisions behind Project Dogwood — what was chosen,
why, what it assumes, and which specifications changed as a result.

## Organization

ADRs are grouped by the layer they affect:

```
adrs/
  layer-1/ADR-001-example.md
  layer-2/ADR-001-example.md
  ...
```

A decision that spans several layers is filed under the layer it most affects, and links to
the others from its **Updated Documents** section.

## When an ADR Is Required

Drafting the initial specifications did not require ADRs. That phase is over. From here on,
**any technical decision, pivot, or resolved assumption requires one** — see `AGENTS.md` §3.

An ADR is valid only when it lists every specification document updated as a result of the
decision. Traceability is the point: a reader should be able to start at any line in a
specification and find the decision that put it there.

## Template

Copy the template from [`AGENTS.md`](../AGENTS.md) §3. It covers: Context & Problem
Statement, Decision, Rationale & Research (with links proving feasibility), Unstated
Assumptions, and Updated Documents.

## Index

_No ADRs recorded yet._
