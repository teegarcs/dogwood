#!/usr/bin/env python3
"""Project Dogwood -- a render test that cannot report anything on the web.

`runComposeUiTest` has a different return type on every target: `Unit` on the Java Virtual Machine
and Kotlin/Native, and `Promise<JsAny?>` on Kotlin/WebAssembly. The test framework awaits that
promise **only if the test function returns it**. So a shared render test written as

    @Test
    fun aThing() {
      render(tree)                       // <- statement; the promise is dropped
      assertEquals(1, tree.skew.size)    // <- runs before the composition, or instead of it
    }

composes nothing on the web. Assertions placed *after* the call read an un-composed tree, and
assertions placed *inside* the block are never observed at all -- a deliberate `fail()` inside a
discarded block was watched to **pass** on that target, which is what settled it.

That is not hypothetical and it is not cheap. Every shared render test was written this way, and
they were green on the web for weeks while composing nothing. The only one sensitive enough to
notice -- a hostile value clamped and reported by a *binding*, during composition -- was recorded
in `plans/conformance.md` as an unexplained platform divergence in the clamp, and moved to the Java
Virtual Machine alone. There was no divergence.

The fix is a shape: an expression body, so the test function returns what the harness returns.

    @Test
    fun aThing() = rendered(tree) {
      assertEquals(1, tree.skew.size)
    }

This is the check for that shape. It is a grep with a reason attached, which is the honest
description of what it can do: it catches the shape, not every way of dropping a promise.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
# Every module with a shared render source set. The Material 3 tier has one too (plans/generator-v2.md, M2).
SOURCES = [
    ROOT / "engine/dogwood-host/src/renderTest/kotlin",
    ROOT / "engine/dogwood-material3/src/renderTest/kotlin",
]

# A call that starts a composition, directly or through one of this source set's helpers.
RENDERS = re.compile(r"\brunComposeUiTest\s*\{|\b(render|rendered|show|showing|scrolling|mounted)\s*\(")


def problems() -> list[str]:
    found = []
    for path in sorted(p for source in SOURCES for p in source.rglob("*.kt")):
        lines = path.read_text().split("\n")
        i = 0
        while i < len(lines):
            header = re.match(r"^  fun (\w+)\(\)( = run \{| = \w+\(| \{)", lines[i])
            if not header:
                i += 1
                continue
            body, j = [], i + 1
            while j < len(lines) and lines[j] != "  }":
                body.append(lines[j])
                j += 1
            renders = any(RENDERS.search(line) for line in body)
            block_bodied = header.group(2) == " {"
            where = f"{path.relative_to(ROOT)}:{i + 1} {header.group(1)}"
            if renders and block_bodied:
                found.append(
                    f"{where} -- renders, but has a block body, so the harness's result is "
                    f"dropped and this test composes nothing on Kotlin/WebAssembly. Use an "
                    f"expression body.",
                )
            elif renders:
                # The last top-level statement must be the harness call, or everything after it is
                # outside the composition on the web and the returned value is not the harness's.
                top = [line for line in body if re.match(r"^    \S", line)]
                if top and top[-1].strip() not in ("}", ")"):
                    found.append(
                        f"{where} -- the last statement is {top[-1].strip()!r} rather than the "
                        f"render block, so it runs outside the composition on Kotlin/WebAssembly.",
                    )
            i = j + 1
    return found


if __name__ == "__main__":
    for source in SOURCES:
        if not source.is_dir():
            sys.exit(f"no shared render tests at {source}")
    found = problems()
    for problem in found:
        print(f"render-shape: {problem}")
    if found:
        print(f"\n{len(found)} render test(s) cannot report anything on the web. See this file's "
              f"header.", file=sys.stderr)
        sys.exit(1)
    print(f"render-shape: every shared render test returns its harness result "
          f"({sum(len(list(source.rglob('*.kt'))) for source in SOURCES)} files)")
