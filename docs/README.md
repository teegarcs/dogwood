# The front door

Everything in this repository is documented, which creates its own problem: four manuals, five
layer specifications, eighty-two architecture decision records and a conformance catalogue, with no
statement of where *you* should start. This page is that statement. Find your row.

| You are… | Read, in order | You can stop when |
|---|---|---|
| **Deciding whether to adopt** | [`README`](../README.md) → [`developer-experience.md`](../developer-experience.md) → [`plans/adoption-audit.md`](../plans/adoption-audit.md) | you have seen the audit — it is the unflattering list, kept current, and a decision made without it is a decision made on the pitch |
| **Adding Dogwood to an application** | [`getting-started.md`](getting-started.md), with [`samples-standalone/umbra/`](../samples-standalone/umbra/) open beside it | Umbra builds and renders on your machine (`tools/standalone-check/run.sh` is the same path, scripted) |
| **Writing screens** | [`authoring.md`](authoring.md) → the [component reference](api/) | you know why each rule exists — a rule whose reason you have not read is a rule you will work around |
| **On call for a product using this** | [`operating.md`](operating.md) | you know the three protections that run without you, how to stop a release, and how to read a skew report and a guest crash |
| **Reviewing this for security** | [`security.md`](security.md), then [`SECURITY.md`](../SECURITY.md) to report something | you can name the boundary that answers each threat in its table, and the two residual risks it says are not prevented — "sandbox" here means capability-confined, not isolated |
| **Running Dogwood across several teams** | [`multi-team.md`](multi-team.md) | you know that two teams means two shells, what the second one costs, and how to resolve a surface-lock conflict without renumbering a tag |
| **Maintaining the engine itself** | [`../high-level-tech-spec-final.md`](../high-level-tech-spec-final.md) → the [layer spec](../specs/) you are touching → its ADRs via [`../adrs/README.md`](../adrs/README.md) → [`checks.md`](checks.md) | you can say which conformance claims your change touches — [`../plans/conformance.md`](../plans/conformance.md) is the list |

Two rules of this documentation, worth knowing before trusting it:

- **The component reference is generated** from the same parse as the bindings, so it cannot
  disagree with the surface. The prose can — which is why every relative link is checked on every
  pull request, and why a claim that stops being true is corrected *in the place it was made*
  rather than in a changelog.
- **The ADRs are the "why".** Every maintenance-phase decision has one, each lists the documents it
  changed, and the index summarises every one of them. When a line of code seems wrong, its ADR usually
  explains what it cost to learn.
