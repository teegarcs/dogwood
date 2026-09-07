#!/usr/bin/env python3
"""Project Dogwood -- every relative link in the documentation resolves.

This repository is mostly prose, and prose rots silently. `plans/production-readiness.md` ends with
the observation that nothing checks it: `developer-experience.md` spent seven phases telling readers
the system did not exist and nothing caught it, "because no check reads prose".

This does not read prose. It reads **links**, which is the part of a document that can be wrong in a
way a machine can see, and it is the part that goes wrong first: a file moves, a source set is
renamed, an ADR is filed under a different number, and the sentence around the link stays perfectly
convincing. Two real breakages existed when it was first run, both from renames that happened
elsewhere:

  - `specs/layer-5-host.md` pointed one directory above the repository for the conformance
    catalogue -- on the line that tells a reader where the catalogue lives.
  - `engine/README.md` linked `jvmAndroidMain`, a source set ADR-041 renamed to `ziplineMain`.

**What it deliberately does not do.** It does not check external addresses -- a network call in a
build gate fails for reasons that have nothing to do with this repository -- and it does not check
anchors within a file. Both are real gaps and both are cheaper to leave open than to make flaky.

`archive/` is excluded: it holds superseded drafts kept for decision history, and their links point
at a repository that no longer exists by design.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SKIP = {"build", "node_modules", ".git", "archive", ".gradle"}
LINK = re.compile(r"\]\(([^)\s#]+)(?:#[^)]*)?\)")


def problems() -> tuple[int, list[str]]:
    found, count = [], 0
    for path in sorted(ROOT.rglob("*.md")):
        if any(part in SKIP for part in path.relative_to(ROOT).parts):
            continue
        count += 1
        for match in LINK.finditer(path.read_text(errors="ignore")):
            target = match.group(1)
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            if not (path.parent / target).resolve().exists():
                found.append(f"{path.relative_to(ROOT)} -> {target}")
    return count, found


if __name__ == "__main__":
    count, found = problems()
    for problem in found:
        print(f"link-check: {problem}")
    if found:
        print(f"\n{len(found)} link(s) point at nothing. A document that sends a reader to a "
              f"missing file is wrong in the way a machine can see.", file=sys.stderr)
        sys.exit(1)
    print(f"link-check: every relative link resolves ({count} markdown files)")
