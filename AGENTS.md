# Project Dogwood Documentation & Architectural Rules

This document governs the creation, review, and maintenance of all technical specifications and Architecture Decision Records (ADRs) for Project Dogwood.

## 1. Core Principles

1. **Zero Assumptions:** Do not guess Application Programming Interface (API) surfaces or technical feasibility. Engineers and agents must use web searches to read source code, link directly to GitHub repositories (e.g., Cash App Zipline, JetBrains Skiko), and cite specific files to prove an architecture is viable.
2. **Always Spell Out Acronyms:** Whenever an acronym is used, it must be spelled out alongside the acronym (e.g., Server-Driven User Interface (SDUI), WebAssembly (Wasm)). Every sentence should be readable without previous context.
3. **Balance Depth and Clarity:** Documentation must contain deep technical plans for implementation, but it must be written clearly enough that engineers without C or WebAssembly (Wasm) backgrounds can fully understand the concepts and logic.
4. **Mermaid Diagrams & Detailed Nodes:** Architectural visuals (sequence diagrams, flowcharts, layer maps) must be maintained as code using Mermaid.js to ensure they evolve with the documents. Every single layer document MUST include a diagram indicating how the layer works. Furthermore, every component, node, or actor represented in a diagram MUST be explicitly detailed and defined in the text of the layer document.

5. **Validate a failure before acting on it.** A red test, a crash, or a suspected bug is a
   *hypothesis*. Reproduce it against the real thing — the running app, the actual API — before
   changing course, altering product code, or drafting an upstream report. A false alarm sends
   people to fix something that is not broken, and it is the more expensive direction of error:
   nothing catches it except going and looking.

   Two accessibility claims failed this way. One read an `AccessibilityNodeInfo`'s own text where
   the screen reader aggregates a subtree, and reported thirteen anonymous controls that announce
   perfectly well. One read a node's `focusable` flag and declared the control unreachable by
   keyboard; pressing Tab took one minute and showed it was reachable.

   The general shape: **prefer the observable consequence to the property that ought to imply it.**
   "The counter went up" is evidence. "The node advertises that it can be focused" is a proxy, and
   a proxy is a second thing that can be wrong.

   The converse rule already applies to gates: a check earns belief by being watched to fail
   without the fix. "The code looks right" is never a gate.

## 2. Document Hierarchy

The documentation is organized as follows:
- **Overview:** `high-level-tech-spec-final.md` (Main entry point, with Mermaid diagrams).
- **Layer Specs:** `specs/layer-X-[name].md` (One for each layer).
- **ADRs:** `adrs/layer-X/ADR-[number]-[name].md` (Decision tracking per layer).

## 3. Architecture Decision Records (ADRs)

1. **Post-Draft Requirement:** During the initial drafting of the specification documents, ADRs are not required. 
2. **Maintenance Phase:** Once the first drafts are complete, any technical decision, pivot, or assumption resolution MUST be documented in an ADR. 
3. **Traceability:** An ADR is only considered valid and complete if it lists the corresponding spec documents that were updated as a result of the decision.

### ADR Template
```markdown
# ADR-[Number]: [Title]

**Date:** YYYY-MM-DD
**Status:** [Proposed | Accepted]

## 1. Context & Problem Statement
What specific issue or architectural choice are we facing?

## 2. Decision
What is the final technical decision?

## 3. Rationale & Research
Why this choice? Link to specific GitHub repository files or WebAssembly (Wasm)/Kotlin documentation that proves feasibility.

## 4. Unstated Assumptions
What must be true for this to work? (e.g., "Assuming Wasm3 supports X").

## 5. Updated Documents
*Must list all specs modified by this decision.*
- [Link to Layer Spec]
```

## 4. Adversarial Peer Review & Sub-Agents

No layer specification is considered complete until it has undergone adversarial peer review.

1. **Review Process:** 2-3 adversarial sub-agents must audit the document for feasibility, memory/performance gaps, and unstated assumptions.
2. **Resolving Findings:** Adversarial findings should not simply be listed at the bottom of a document. The findings must result in actual fixes, clarifications, and updates to the document text itself. 

### Sub-Agent Prompts
When spinning up sub-agents for review, use the following roles:
- **Performance/Memory Agent:** Focuses strictly on memory leaks, zero-copy pointer risks, thread blocking, and garbage collection bottlenecks.
- **Integration/Boundaries Agent:** Focuses strictly on the Foreign Function Interface (FFI) boundary, data marshalling, and API mismatches between layers.
- **Unknown Assumptions Agent:** Focuses on finding statements presented as facts that require proof or links to external documentation.

## 5. Layer Specification Template

Every layer document in `specs/` must follow this structure:

```markdown
# Layer [X]: [Name]

## 1. Responsibilities & Scope
What are the exact boundaries of this layer? What does it NOT do?

## 2. Technical Stack & Dependencies
- Language, framework, and specific libraries.
- Required: Inline links to official documentation or GitHub repositories.

## 3. Internal Architecture
- How does the layer operate internally? 
- Required: Mermaid flowchart or sequence diagram.

## 4. Interfaces & Foreign Function Interface (FFI) Boundary
- Inputs: Exact data format received from Layer [X-1].
- Outputs: Exact data format passed to Layer [X+1].
- Memory Ownership: Who owns the data buffers?

## 5. Implementation Roadmap
- Step-by-step technical milestones for building this layer.
```
