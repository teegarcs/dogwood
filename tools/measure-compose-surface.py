#!/usr/bin/env python3
"""
Measure the bindability of the Jetpack Compose public API surface for Project Dogwood.

Reads metalava signature dumps in tools/api-dumps/ (pinned to the commit in SOURCE.txt)
and classifies every public @Composable UI function by whether Dogwood can GENERATE a
binding for it, or whether it needs a hand-written bespoke subsystem.

The rule (see specs/layer-5-host.md, "Bindability: The Real Rule"):
  A composable is GENERABLE only if every lambda parameter is either
    (a) materialised once at composition time (a content slot), or
    (b) a discrete fire-and-forget event,
  and no parameter is a live object the guest must read or call, and no
  parameter is a layout-engine type or a generic type variable.

The metalava dumps are fetched on demand from the androidx commit pinned in
tools/api-dumps/SOURCE.txt and cached locally (they are not committed -- they are
~2.9 MB of third-party files). Fetching requires the `gh` CLI and network access.

Run:  python3 tools/measure-compose-surface.py
"""
import re, glob, os, collections, sys, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
DUMPS = os.path.join(HERE, "api-dumps")

PRIM = {"boolean","int","long","float","double","char","byte","short","void"}
STRINGY = {"String","CharSequence","AnnotatedString"}
# Kotlin inline value classes over primitives -> marshal by value
VALUE = {"Dp","DpSize","DpOffset","DpRect","Color","TextUnit","IntSize","IntOffset","Size","Offset",
         "IntRect","Rect","FontWeight","BlendMode","TextAlign","TextDecoration","TextDirection",
         "FontStyle","FontSynthesis","Constraints","TileMode","StrokeCap","StrokeJoin",
         "PathFillType","ClipOp","FilterQuality","ImageBitmapConfig","KeyboardType","ImeAction"}

# Scopes whose lambdas the HOST engine invokes inside a frame traversal -> NOT generable.
IN_FRAME_SCOPES = {"DrawScope","ContentDrawScope","LazyListScope","LazyGridScope","LazyStaggeredGridScope",
                   "LazyItemScope","PointerInputScope","AwaitPointerEventScope","MeasureScope",
                   "SubcomposeMeasureScope","BoxWithConstraintsScope","PagerScope","CacheDrawScope",
                   "LookaheadScope","GraphicsLayerScope"}
# Layout-engine types the guest cannot hold.
ENGINE = {"MeasurePolicy","MeasureScope","Density","Composer","SubcomposeMeasureScope","FontFamily.Resolver"}
# Live state holders the guest must READ or CALL -> bespoke mirrored-state protocol.
LIVE_STATE = {"LazyListState","LazyGridState","LazyStaggeredGridState","ScrollState","PagerState",
              "SnackbarHostState","DrawerState","BottomSheetState","SheetState","FocusRequester",
              "FocusManager","TextFieldState","TextFieldValue","MutableInteractionSource",
              "InteractionSource","TooltipState","SearchBarState","BottomAppBarScrollBehavior",
              "TopAppBarScrollBehavior","ScrollableState","FlingBehavior","OverscrollEffect",
              "NestedScrollConnection","TransformableState","DraggableState","AnchoredDraggableState"}
# Composition-control constructs: execute in the guest, never dispatched as a widget.
RUNTIME_CONTROL = {"LaunchedEffect","DisposableEffect","SideEffect","CompositionLocalProvider",
                   "ComposeNode","ReusableComposeNode","ReusableContent","ReusableContentHost",
                   "key","remember","movableContentOf"}
ANDROID_ONLY_PREFIX = ("Android",)

def split_top(s):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "<([": depth += 1
        elif ch in ">)]": depth -= 1
        if ch == "," and depth == 0:
            out.append(cur); cur = ""
        else: cur += ch
    if cur.strip(): out.append(cur)
    return [x.strip() for x in out]

def head(ty):
    ty = ty.strip().replace("!","").replace("?","")
    ty = re.sub(r"^(optional|vararg)\s+","",ty)
    ty = re.sub(r"<.*>$","",ty)
    return ty.rsplit(".",1)[-1]

def param_type(p):
    p = re.sub(r"^(optional\s+)?","",p.strip())
    toks = p.split()
    return " ".join(toks[:-1]).strip() if len(toks) > 1 else p

def classify_param(raw):
    ty = param_type(raw); h = head(ty)
    optional = raw.strip().startswith("optional")
    if raw.strip().startswith("vararg"): return "EXCL_vararg"
    if re.match(r"^[A-Z]$", h): return "EXCL_generic"
    if h in ENGINE: return "EXCL_engine"
    if h in LIVE_STATE: return "OPT_livestate" if optional else "BESPOKE_livestate"
    if h in PRIM: return "GEN_primitive"
    if h in STRINGY: return "GEN_string"
    if h in VALUE: return "GEN_value"
    if h == "Modifier": return "BESPOKE_modifier"
    if h.startswith("Function"):
        inner = re.search(r"<(.*)>", ty)
        args = split_top(inner.group(1)) if inner else []
        recv = head(args[0]) if args else ""
        ret = head(args[-1]) if args else "Unit"
        if recv in IN_FRAME_SCOPES: return "EXCL_inframe_lambda"
        if ret in ("Unit","void"): return "GEN_slot_or_event"
        return "BESPOKE_lambda_returns"
    return "GEN_deferred_expr"

def ensure_dumps():
    """Fetch the pinned metalava dumps if they are not already cached."""
    src = os.path.join(DUMPS, "SOURCE.txt")
    if not os.path.exists(src):
        print(f"Missing {src}; cannot determine the pinned commit.", file=sys.stderr)
        return False
    lines = [l.strip() for l in open(src) if l.strip()]
    sha = lines[0].split(":")[-1].strip()
    modules = [l for l in lines[1:] if "/" in l]
    missing = [m for m in modules
               if not os.path.exists(os.path.join(DUMPS, m.replace("/", "_") + ".txt"))]
    if not missing:
        return True
    print(f"Fetching {len(missing)} API dumps at androidx {sha[:12]} ...", file=sys.stderr)
    for m in missing:
        dest = os.path.join(DUMPS, m.replace("/", "_") + ".txt")
        r = subprocess.run(
            ["gh", "api", f"repos/androidx/androidx/contents/{m}/api/current.txt?ref={sha}",
             "-H", "Accept: application/vnd.github.raw"],
            capture_output=True, text=True)
        if r.returncode != 0 or not r.stdout:
            print(f"  failed: {m}", file=sys.stderr); return False
        open(dest, "w", encoding="utf-8").write(r.stdout)
        print(f"  {m}", file=sys.stderr)
    return True

def main():
    if not ensure_dumps(): return 1
    rows, per_module = [], collections.defaultdict(collections.Counter)
    params = collections.Counter(); excl_reasons = collections.Counter()
    deprecated = 0
    files = sorted(glob.glob(os.path.join(DUMPS, "*.txt")))
    files = [f for f in files if not f.endswith("SOURCE.txt")]
    if not files:
        print("No dumps found in tools/api-dumps/", file=sys.stderr); return 1
    for f in files:
        mod = os.path.basename(f).replace("compose_","").replace(".txt","")
        for line in open(f, encoding="utf-8", errors="replace"):
            if "@androidx.compose.runtime.Composable" not in line: continue
            if "@BytecodeOnly" in line: continue
            m = re.search(r"\b(\w+)\s*\((.*)\);\s*$", line)
            if not m: continue
            name, plist = m.group(1), m.group(2)
            if not name[:1].isupper(): continue
            if name in RUNTIME_CONTROL:
                per_module[mod]["runtime_control"] += 1; continue
            if name.startswith(ANDROID_ONLY_PREFIX):
                per_module[mod]["android_only"] += 1; continue
            if "@Deprecated" in line: deprecated += 1
            ps = split_top(plist) if plist.strip() else []
            cs = [classify_param(p) for p in ps]
            for c in cs: params[c] += 1
            if any(c.startswith("EXCL") for c in cs):
                verdict = "excluded"
                for c in cs:
                    if c.startswith("EXCL"): excl_reasons[c] += 1
            elif any(c == "BESPOKE_livestate" or c == "BESPOKE_lambda_returns" for c in cs):
                verdict = "live_state"
            elif any(c == "BESPOKE_modifier" for c in cs):
                verdict = "modifier_only"
            elif any(c == "OPT_livestate" for c in cs):
                verdict = "modifier_only"
            else:
                verdict = "generable"
            rows.append((mod, name, verdict))
            per_module[mod][verdict] += 1

    tot = len(rows)
    print(f"Source: {open(os.path.join(DUMPS,'SOURCE.txt')).readline().strip()}")
    print(f"Modules measured: {len(files)}\n")
    print(f"Public @Composable UI functions in scope: {tot}")
    print(f"  (excluded before classification: "
          f"{sum(c['runtime_control'] for c in per_module.values())} composition-control, "
          f"{sum(c['android_only'] for c in per_module.values())} Android-only)")
    print(f"  of which @Deprecated: {deprecated} ({100*deprecated/tot:.1f}%)\n")
    v = collections.Counter(r[2] for r in rows)
    for k, label in [("generable","GENERABLE now          - no bespoke dependency"),
                     ("modifier_only","GENERABLE after Modifier - only dependency is the Modifier subsystem"),
                     ("live_state","NEEDS LIVE-STATE proto - REQUIRED host-owned state holder"),
                     ("excluded","EXCLUDED               - in-frame lambda, engine type, or generic")]:
        print(f"  {k:14s} {v[k]:4d}  ({100*v[k]/tot:5.1f}%)  {label}")
    reachable = v['generable'] + v['modifier_only']
    print(f"\n  => Generable once the Modifier subsystem exists: {reachable} / {tot} = {100*reachable/tot:.1f}%")
    print(f"     (includes composables whose only live-state parameter is OPTIONAL and may be omitted;")
    print(f"      those parameters are unavailable to guest code until a live-state protocol exists)")
    print(f"  => Requires a per-holder live-state protocol:     {v['live_state']} ({100*v['live_state']/tot:.1f}%)")
    print(f"  => Structurally unreachable:                      {v['excluded']} ({100*v['excluded']/tot:.1f}%)")
    print("\nPer module:")
    for mod in sorted(per_module):
        c = per_module[mod]; n = c['generable']+c['modifier_only']+c['live_state']+c['excluded']
        if not n: continue
        print(f"  {mod:32s} n={n:4d}  gen={c['generable']:3d}  +mod={c['modifier_only']:4d}  live={c['live_state']:4d}  excl={c['excluded']:3d}")
    print("\nWhy composables are excluded:")
    for k, n in excl_reasons.most_common(): print(f"  {k:24s} {n:4d}")
    print("\nParameter class distribution:")
    tp = sum(params.values())
    for k, n in params.most_common(): print(f"  {k:24s} {n:5d}  ({100*n/tp:5.1f}%)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
