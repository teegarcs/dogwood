#!/usr/bin/env python3
"""Project Dogwood -- turn a minified guest stack back into Kotlin file and line numbers.

    tools/symbolicate/resolve.py <source-map> [stack-file]

Reads a stack on standard input (or from a file) and rewrites every frame that points into the
mapped bundle. Frames it cannot resolve are printed unchanged rather than dropped, because a partial
stack that says so is more useful than a short one that does not.

## Why this is a tool and not a feature of the host

A guest crash on the web now reaches the host with its stack (ADR-063). A **production** webpack
build mangles the names in that stack but keeps exact line and column offsets into the bundle:

    bs: dogwood deliberate guest crash: CRASH-COMPOSED
        at bn.p8 (http://.../guest-kotlin.js:1:419445)
        at er.o8 (http://.../guest-kotlin.js:1:90351)

`bn.p8` is not a function name anyone can act on. `1:419445` is, because the source map the build
already produces resolves it to a Kotlin file and line. So the frames are worth carrying and the
resolution is worth doing -- somewhere other than in the browser.

**The source map is deliberately not served.** `samples/web-slice/build.gradle.kts` copies
`guest-kotlin.js` into the distribution and not `guest-kotlin.js.map`, because a public source map
hands every reader the payload's Kotlin source. That is a choice, and it is the reason symbolication
is an offline step against a build artefact rather than a host feature: the host has neither the map
nor any business fetching one.

This decodes the Source Map v3 `mappings` field directly -- Base64 variable-length-quantity segments,
described at https://sourcemaps.info/spec.html -- rather than depending on a package, so it runs
wherever Python does.
"""
import json
import re
import sys

BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
DIGIT = {c: i for i, c in enumerate(BASE64)}


def decode_vlq(segment: str):
    """Decodes one comma-free segment into its list of signed integers."""
    values, shift, accumulator = [], 0, 0
    for character in segment:
        digit = DIGIT[character]
        accumulator += (digit & 0x1F) << shift
        if digit & 0x20:
            shift += 5
            continue
        # The low bit is the sign, which is why this is not a plain shift.
        value = accumulator >> 1
        values.append(-value if accumulator & 1 else value)
        shift, accumulator = 0, 0
    return values


def build_index(source_map):
    """Returns {generated_line: [(generated_column, source_index, source_line, source_column)]}.

    Sorted by column, so a lookup is a scan for the last mapping at or before the column asked for --
    which is what the specification means by a mapping: it holds from its column until the next one.
    """
    index = {}
    source_index = source_line = source_column = 0
    for generated_line, group in enumerate(source_map["mappings"].split(";")):
        generated_column = 0
        entries = []
        for segment in group.split(","):
            if not segment:
                continue
            fields = decode_vlq(segment)
            generated_column += fields[0]
            if len(fields) >= 4:
                source_index += fields[1]
                source_line += fields[2]
                source_column += fields[3]
                entries.append((generated_column, source_index, source_line, source_column))
        if entries:
            index[generated_line] = sorted(entries)
    return index


def resolve(index, sources, line, column, lengths=None):
    """One frame as (source, line, column, note), or None when nothing covers it.

    `note` is empty for an ordinary hit. It says something when the mapping the specification gives
    is **not a position in the file it names** -- which happens, and pretending otherwise would be
    the mistake this project keeps finding. Kotlin/JavaScript emits mappings for code it synthesised,
    and those can point past the end of the source: a frame for the `error(...)` in `CrashScreen.kt`
    resolves to line 71, column 436, in a file of 70 lines whose longest line is nowhere near 436
    characters. The mapping six bytes later in the bundle points at line 68, which is where the call
    actually is.

    So an out-of-range position is reported *and* the nearest in-range mapping for the same source is
    offered beside it, clearly marked. Silently substituting the plausible one would be inventing an
    answer; dropping it would throw away the only frame anybody cares about.
    """
    entries = index.get(line - 1)
    if not entries:
        return None
    best = best_at = None
    for position, entry in enumerate(entries):
        # `<=`, not `<`: a mapping starts *at* its column.
        if entry[0] <= column - 1:
            best, best_at = entry, position
        else:
            break
    if best is None:
        return None
    _, source_index, source_line, source_column = best
    source = sources[source_index]

    lines = (lengths or {}).get(source)
    if lines is None or in_range(lines, source_line, source_column):
        return source, source_line + 1, source_column + 1, ""
    length = len(lines)

    # Out of range. Walk outwards for the nearest mapping into the same source that is in range.
    for offset in range(1, 40):
        for neighbour in (best_at + offset, best_at - offset):
            if 0 <= neighbour < len(entries):
                _, other_source, other_line, other_column = entries[neighbour]
                if sources[other_source] == source and in_range(lines, other_line, other_column):
                    return (
                        source,
                        source_line + 1,
                        source_column + 1,
                        f"  <- not a position in this file ({length} lines); nearest real mapping "
                        f"{other_line + 1}:{other_column + 1}",
                    )
    return (
        source,
        source_line + 1,
        source_column + 1,
        f"  <- not a position in this file ({length} lines)",
    )


def in_range(lines, line, column):
    """Is (line, column) a place that exists in this source?

    Both halves are checked, and the column half is the one that catches the interesting case: a
    mapping can name a line that exists and a column two hundred characters past its end.
    """
    return 0 <= line < len(lines) and column <= len(lines[line])


FRAME = re.compile(r"^(\s*at .*?)\(?([^\s(]+):(\d+):(\d+)\)?\s*$")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    with open(sys.argv[1]) as handle:
        source_map = json.load(handle)
    index = build_index(source_map)
    sources = source_map["sources"]
    # Each source's real lines, read from the map's own embedded copy. This is what makes an
    # out-of-range mapping detectable rather than merely wrong.
    #
    # `rstrip("\n")` before splitting, because a file ending in a newline otherwise reports one line
    # more than it has -- and that phantom last line is exactly where a bogus mapping lands, so the
    # off-by-one would hide the thing this check exists to find.
    contents = source_map.get("sourcesContent") or []
    lengths = {
        source: content.rstrip("\n").split("\n")
        for source, content in zip(sources, contents)
        if content
    }

    text = open(sys.argv[2]).read() if len(sys.argv) > 2 else sys.stdin.read()
    # The host reports a stack as one line with escaped newlines when it comes out of a JavaScript
    # Object Notation log; accept either form rather than making the caller normalise it.
    resolved = unresolved = 0
    for raw in text.replace("\\n", "\n").splitlines():
        match = FRAME.match(raw)
        if not match:
            print(raw)
            continue
        prefix, _, line, column = match.groups()
        position = resolve(index, sources, int(line), int(column), lengths)
        if position is None:
            unresolved += 1
            print(f"{raw}   <- no mapping")
        else:
            resolved += 1
            source, source_line, source_column, note = position
            print(f"{prefix.rstrip()} {source}:{source_line}:{source_column}{note}")
    print(f"\n-- {resolved} frames resolved, {unresolved} unresolved", file=sys.stderr)
    # Non-zero when nothing resolved: a symbolicator that silently produces the input it was given
    # is one whose output nobody checks.
    return 0 if resolved else 1


sys.exit(main())
