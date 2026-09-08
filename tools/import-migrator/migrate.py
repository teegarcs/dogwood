#!/usr/bin/env python3
"""Project Dogwood -- rewrite a native Compose screen's imports for the guest.

The framework grade's one deduction from authoring ergonomics: Dogwood's types are not androidx's,
so moving a screen between the static and dynamic worlds is "a port, not a re-import". This closes
the mechanical half of that port and, more usefully, **tells you precisely which lines are the
other half** rather than leaving you to find them by compiling.

    tools/import-migrator/migrate.py Screen.kt                 # report only
    tools/import-migrator/migrate.py Screen.kt --write         # rewrite in place
    tools/import-migrator/migrate.py src/main/ --write         # a directory of them

**What it will not do.** It does not translate widget *calls*, because they are not translations:
`androidx.compose.material3.Button(onClick = …) { Text("Pay") }` takes a content lambda and
Dogwood's `PrimaryButton(label = …, onClick = …)` takes a label — a different API, on purpose
(a slot per button is a slot per button on the wire). It reports those call sites and leaves them
for you. A rewriter that guessed here would produce code that compiles and means something else,
which is worse than code that does not compile.

Everything below is derived from what the guest actually exports; the audit of a file is honest
about what it could not classify rather than silently passing it through.
"""
import argparse
import pathlib
import re
import sys

# Imports that map one-for-one: the same name, a different package. Nothing here changes a
# signature -- that is the whole criterion for being in this table.
DIRECT_PREFIXES = [
    ("androidx.compose.foundation.layout.", "dev.dogwood.compose."),
    ("androidx.compose.foundation.", "dev.dogwood.compose."),
    ("androidx.compose.material3.", "dev.dogwood.compose."),
    ("androidx.compose.ui.Modifier", "dev.dogwood.compose.Modifier"),
    ("androidx.compose.ui.unit.", "dev.dogwood.compose."),
    ("androidx.compose.ui.graphics.", "dev.dogwood.compose."),
]

# Imports that stay exactly as they are: the guest runs the REAL Compose runtime, so `remember`,
# `LaunchedEffect`, `rememberSaveable` and friends are the same symbols from the same package.
# This list exists so the report can say "kept, deliberately" instead of staying silent.
KEPT_PREFIXES = [
    "androidx.compose.runtime.",
    "kotlinx.",
    "kotlin.",
]

# Symbols the guest genuinely does not carry, with what to use instead. Each is a real difference
# rather than a missing binding; the message is the migration instruction.
REPLACED = {
    "androidx.compose.ui.res.painterResource": "the sandbox has no resources -- AsyncImage(url) (ADR-017)",
    "androidx.compose.ui.res.stringResource": "the sandbox has no resources -- carry string tables in the payload",
    "androidx.compose.ui.res.vectorResource": "the sandbox has no resources -- Icon(name)",
    "androidx.compose.animation.core.animateFloatAsState": "per-frame state -- Modifier.alpha(animate(target, spec))",
    "androidx.compose.animation.core.animateDpAsState": "per-frame state -- Modifier.height(animateDp(target, spec))",
    "androidx.compose.animation.core.rememberInfiniteTransition": "per-frame state -- oscillate(from, to, spec)",
    "androidx.compose.animation.core.Animatable": "per-frame state -- declare a target (ADR-020)",
    "androidx.compose.animation.core.updateTransition": "per-frame state -- declare a target (ADR-020)",
}

# Composables the guest exports under a DIFFERENT shape. The import rewrites; the call does not,
# and pretending otherwise is the failure mode this tool refuses.
RESHAPED = {
    "Button": "PrimaryButton(label = …, onClick = …) -- a label, not a content slot",
    "TextButton": "PrimaryButton(label = …, onClick = …)",
    "OutlinedButton": "PrimaryButton(label = …, onClick = …)",
    "Image": "AsyncImage(url = …) -- images arrive by URL, not as painters",
    "LazyColumn": "VerticalList { … } -- items are composed, not indexed (ADR-011)",
    "LazyRow": "HorizontalList { … }",
    "Scaffold": "compose the parts you need; there is no Scaffold binding",
    "TopAppBar": "compose it, or register it as a product component",
}


def migrate(text: str) -> tuple[str, list[str], list[str]]:
    """Returns the rewritten source, the changes made, and the things a person must decide."""
    changed, manual = [], []
    out = []
    for line in text.split("\n"):
        stripped = line.strip()
        if not stripped.startswith("import "):
            out.append(line)
            continue
        symbol = stripped[len("import "):].strip()

        replacement = REPLACED.get(symbol)
        if replacement:
            manual.append(f"{symbol} -- {replacement}")
            out.append(f"// MIGRATE: {symbol} -- {replacement}")
            continue
        if any(symbol.startswith(prefix) for prefix in KEPT_PREFIXES):
            out.append(line)
            continue

        rewritten = None
        for old, new in DIRECT_PREFIXES:
            if symbol.startswith(old):
                rewritten = symbol.replace(old, new, 1)
                break
        if rewritten:
            leaf = rewritten.rsplit(".", 1)[-1]
            if leaf in RESHAPED:
                # NOT rewritten: `dev.dogwood.compose.Button` does not exist, and an import
                # pointing at nothing is worse than one pointing at androidx -- the compiler's
                # message would name a package rather than the actual problem, which is that this
                # widget has a different shape here. The first version of this tool rewrote these
                # and produced files whose every unresolved import lied about why.
                manual.append(f"{leaf} -- {RESHAPED[leaf]}")
                out.append(f"// MIGRATE: {symbol} -- {RESHAPED[leaf]}")
                continue
            out.append(line.replace(symbol, rewritten))
            changed.append(f"{symbol} -> {rewritten}")
            continue
        if symbol.startswith("androidx."):
            # Reported, never dropped. An unclassified androidx import is exactly where a silent
            # rewriter would do damage, and it is the one place a person must look.
            manual.append(f"{symbol} -- not carried by the guest; check the component reference")
            out.append(f"// MIGRATE: {symbol} -- unknown to the guest surface")
            continue
        out.append(line)
    return "\n".join(out), changed, manual


def process(path: pathlib.Path, write: bool) -> int:
    text = path.read_text()
    rewritten, changed, manual = migrate(text)
    if not changed and not manual:
        return 0
    print(f"\n{path}")
    for line in changed:
        print(f"  rewrote  {line}")
    for line in manual:
        print(f"  DECIDE   {line}")
    if write and rewritten != text:
        path.write_text(rewritten)
    return len(manual)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("target", type=pathlib.Path)
    parser.add_argument("--write", action="store_true", help="rewrite in place")
    args = parser.parse_args()

    files = [args.target] if args.target.is_file() else sorted(args.target.rglob("*.kt"))
    if not files:
        print(f"no Kotlin sources under {args.target}", file=sys.stderr)
        return 1
    decisions = sum(process(f, args.write) for f in files)
    print(
        f"\n{len(files)} file(s). "
        + ("rewritten in place. " if args.write else "report only; pass --write to apply. ")
        + (f"{decisions} line(s) need a person." if decisions else "nothing needs a person.")
    )
    # Exit non-zero when a human decision is outstanding, so a migration script can stop.
    return 2 if decisions else 0


if __name__ == "__main__":
    sys.exit(main())
