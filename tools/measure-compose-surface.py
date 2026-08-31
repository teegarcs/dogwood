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
  and no parameter is a live object the guest must read or call, no parameter is an
  object carrying host-invoked callbacks, no parameter is asset-backed (Painter,
  ImageBitmap, ImageVector), and no parameter is a layout-engine type or a generic
  type variable.

MEASUREMENT SCOPE — read before quoting numbers. This classifier measures the
UPPERCASE (widget-shaped) @Composable surface only. The lowercase @Composable
surface — defaults factories (`ButtonDefaults.buttonColors`), `remember*` state
factories, `animate*AsState` and the rest of the animation-state API, and
`*Resource` loaders — is roughly the same size again (~453 functions at the pinned
commit) and is REPORTED but not classified, because those functions are not
dispatched as widgets. They are not free: defaults factories ride on the
deferred-expression protocol, `remember*` factories each imply a live-state
protocol entry, `animate*` requires the host-side animation subsystem, and
`*Resource` requires the resources subsystem. See adrs/layer-5/ADR-005.

An earlier revision of this script (the one that produced the 450 / 81.1% figures)
had two one-directional optimistic defects, both fixed here: it captured annotation
names (`RequiresApi`) as function names for 5 rows, and it classified every unknown
object type as a generable deferred expression, which miscounted ~25 live-state
holder types, callback-carrying objects (KeyboardActions, VisualTransformation),
and asset-backed types (Painter) as generable. See adrs/layer-5/ADR-005.

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
ENGINE = {"MeasurePolicy","MultiContentMeasurePolicy","LazyLayoutMeasurePolicy","MeasureScope","Density",
          "Composer","SubcomposeMeasureScope","FontFamily.Resolver","GraphicsLayer",
          "PlatformTextInputInterceptor","SubcomposeLayoutState","Object"}
# Live state holders the guest must READ or CALL -> bespoke mirrored-state protocol.
LIVE_STATE = {"LazyListState","LazyGridState","LazyStaggeredGridState","ScrollState","PagerState",
              "SnackbarHostState","DrawerState","BottomSheetState","SheetState","FocusRequester",
              "FocusManager","TextFieldState","TextFieldValue","MutableInteractionSource",
              "InteractionSource","TooltipState","SearchBarState","BottomAppBarScrollBehavior",
              "TopAppBarScrollBehavior","ScrollableState","FlingBehavior","OverscrollEffect",
              "NestedScrollConnection","TransformableState","DraggableState","AnchoredDraggableState",
              # Added after adversarial re-review (ADR-005): these fell through to the
              # optimistic deferred-expression default and were miscounted as generable.
              "TimePickerState","SliderState","RangeSliderState","CarouselState","DatePickerState",
              "DateRangePickerState","WideNavigationRailState","PullToRefreshState","PullRefreshState",
              "SwipeToDismissBoxState","DismissState","ScaffoldState","BottomSheetScaffoldState",
              "BackdropScaffoldState","BottomDrawerState","ModalBottomSheetState","MutableTransitionState",
              "Transition","DeferredTransition","SnackbarData","ScrollFieldState","SelectionState",
              "BasicTooltipState","AppBarMenuState","ButtonGroupMenuState","SliderPositions",
              "TargetedFlingBehavior","FloatingToolbarScrollBehavior","SearchBarScrollBehavior",
              "LazyLayoutPrefetchState","LazyLayoutPinnedItemList"}
# Objects carrying callbacks the HOST invokes (per keystroke, per layout, per draw).
# The guest can name a stock implementation via a deferred expression but can never
# supply its own behaviour, so a REQUIRED parameter of these types is bespoke work.
CALLBACK_OBJ = {"KeyboardActions","KeyboardActionHandler","VisualTransformation","InputTransformation",
                "OutputTransformation","TextFieldDecorator","PopupPositionProvider",
                "DropdownMenuPositionProvider","DatePickerFormatter","SelectableDates","ColorProducer"}
# Asset-backed types: their bytes come from host resources or a loader the sandboxed
# guest does not have. Requires the resources subsystem (ADR-005).
ASSET = {"Painter","ImageBitmap","ImageVector"}
# Controlled text-input widgets: excluded BY NAME per the rule in specs/layer-5-host.md --
# the String-value overloads are exactly the controlled component the spec forbids, and
# an overload-blind type check cannot catch them.
TEXT_INPUT_WIDGETS = {"BasicTextField","TextField","OutlinedTextField","SecureTextField",
                      "BasicSecureTextField","SearchBar","DockedSearchBar"}
# Composition-control constructs: execute in the guest, never dispatched as a widget.
RUNTIME_CONTROL = {"LaunchedEffect","DisposableEffect","SideEffect","CompositionLocalProvider",
                   "ComposeNode","ReusableComposeNode","ReusableContent","ReusableContentHost",
                   "key","remember","movableContentOf"}
ANDROID_ONLY_PREFIX = ("Android",)

ANNOT = re.compile(r"@[\w.]+(?:\([^)]*\))?\s*")

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
    if h in CALLBACK_OBJ: return "OPT_callback" if optional else "BESPOKE_callback"
    if h in ASSET: return "OPT_asset" if optional else "BESPOKE_asset"
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

def lowercase_category(mod, name):
    """Coarse disposition of the lowercase @Composable surface (reported, not classified)."""
    if name.startswith("animate") or "Transition" in name: return "animation (host-side animation subsystem)"
    if name.endswith("Resource"): return "resource loader (resources subsystem)"
    if mod.startswith("runtime"): return "guest runtime (works in guest as-is)"
    if name.startswith("collectIs"): return "live-state read (live-state protocol)"
    if name.startswith("remember"): return "state factory (live-state protocol)"
    if name.startswith("collect") or name.startswith("produce"): return "guest runtime (works in guest as-is)"
    return "defaults factory / getter (deferred-expression protocol)"

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
    bespoke_reasons = collections.Counter()
    lowercase = collections.Counter(); lowercase_total = 0
    deprecated = 0
    gen_with_deferred = 0
    files = sorted(glob.glob(os.path.join(DUMPS, "*.txt")))
    files = [f for f in files if not f.endswith("SOURCE.txt")]
    if not files:
        print("No dumps found in tools/api-dumps/", file=sys.stderr); return 1
    for f in files:
        mod = os.path.basename(f).replace("compose_","").replace(".txt","")
        for line in open(f, encoding="utf-8", errors="replace"):
            if "@androidx.compose.runtime.Composable" not in line: continue
            if "@BytecodeOnly" in line: continue
            clean = ANNOT.sub("", line)
            m = re.search(r"\b([\w-]+)\s*\((.*)\);\s*$", clean)
            if not m: continue
            name, plist = m.group(1), m.group(2)
            name = name.split("-")[0]  # strip inline-class mangle suffix if present
            if not name[:1].isupper():
                lowercase[lowercase_category(mod, name)] += 1
                lowercase_total += 1
                continue
            if name in RUNTIME_CONTROL:
                per_module[mod]["runtime_control"] += 1; continue
            if name.startswith(ANDROID_ONLY_PREFIX):
                per_module[mod]["android_only"] += 1; continue
            if "@Deprecated" in line: deprecated += 1
            ps = split_top(plist) if plist.strip() else []
            cs = [classify_param(p) for p in ps]
            for c in cs: params[c] += 1
            if name in TEXT_INPUT_WIDGETS:
                verdict = "bespoke"; bespoke_reasons["NAME_text_input_widget"] += 1
            elif any(c.startswith("EXCL") for c in cs):
                verdict = "excluded"
                for c in cs:
                    if c.startswith("EXCL"): excl_reasons[c] += 1
            elif any(c in ("BESPOKE_livestate","BESPOKE_callback","BESPOKE_asset","BESPOKE_lambda_returns") for c in cs):
                verdict = "bespoke"
                for c in cs:
                    if c in ("BESPOKE_livestate","BESPOKE_callback","BESPOKE_asset","BESPOKE_lambda_returns"):
                        bespoke_reasons[c] += 1
            elif any(c == "BESPOKE_modifier" for c in cs):
                verdict = "modifier_only"
            elif any(c.startswith("OPT_") for c in cs):
                verdict = "modifier_only"
            else:
                verdict = "generable"
            if verdict in ("generable","modifier_only") and any(c == "GEN_deferred_expr" for c in cs):
                gen_with_deferred += 1
            rows.append((mod, name, verdict))
            per_module[mod][verdict] += 1

    tot = len(rows)
    print(f"Source: {open(os.path.join(DUMPS,'SOURCE.txt')).readline().strip()}")
    print(f"Modules measured: {len(files)}\n")
    print(f"Public UPPERCASE (widget-shaped) @Composable functions in scope: {tot}")
    print(f"  (excluded before classification: "
          f"{sum(c['runtime_control'] for c in per_module.values())} composition-control, "
          f"{sum(c['android_only'] for c in per_module.values())} Android-only)")
    print(f"  of which @Deprecated: {deprecated} ({100*deprecated/tot:.1f}%)\n")
    v = collections.Counter(r[2] for r in rows)
    for k, label in [("generable","GENERABLE now          - no bespoke dependency"),
                     ("modifier_only","GENERABLE after Modifier - only shared dependency is the Modifier subsystem"),
                     ("bespoke","NEEDS BESPOKE subsystem - live state, callback object, asset, or text input"),
                     ("excluded","EXCLUDED               - in-frame lambda, engine type, or generic")]:
        print(f"  {k:14s} {v[k]:4d}  ({100*v[k]/tot:5.1f}%)  {label}")
    reachable = v['generable'] + v['modifier_only']
    print(f"\n  => Generable once the Modifier subsystem exists: {reachable} / {tot} = {100*reachable/tot:.1f}%")
    print(f"     CAVEAT 1: {gen_with_deferred} of those {reachable} ({100*gen_with_deferred/reachable:.1f}%) carry at least one")
    print(f"     deferred-expression parameter, so full use also requires the deferred-expression")
    print(f"     protocol (a second unbuilt subsystem). 'After Modifier' is not a single gate.")
    print(f"     CAVEAT 2: includes composables whose only live-state/callback/asset parameter is")
    print(f"     OPTIONAL and may be omitted; those parameters are unavailable to guest code until")
    print(f"     the corresponding bespoke subsystem exists.")
    print(f"  => Requires a bespoke subsystem:                  {v['bespoke']} ({100*v['bespoke']/tot:.1f}%)")
    print(f"  => Structurally unreachable:                      {v['excluded']} ({100*v['excluded']/tot:.1f}%)")
    print("\nPer module:")
    for mod in sorted(per_module):
        c = per_module[mod]; n = c['generable']+c['modifier_only']+c['bespoke']+c['excluded']
        if not n: continue
        print(f"  {mod:32s} n={n:4d}  gen={c['generable']:3d}  +mod={c['modifier_only']:4d}  bespoke={c['bespoke']:4d}  excl={c['excluded']:3d}")
    print("\nWhy composables need a bespoke subsystem:")
    for k, n in bespoke_reasons.most_common(): print(f"  {k:26s} {n:4d}")
    print("\nWhy composables are excluded:")
    for k, n in excl_reasons.most_common(): print(f"  {k:24s} {n:4d}")
    names = collections.Counter(r[1] for r in rows)
    multi = {k: n for k, n in names.items() if n > 1}
    peak = max(multi.items(), key=lambda kv: kv[1]) if multi else ("-", 0)
    print(f"\nOverload pressure: {len(multi)} of {len(names)} distinct widget names carry more than one")
    print(f"overload; the maximum is {peak[1]} for {peak[0]}. A protocol tag must identify exactly one signature.")
    print("\nParameter class distribution:")
    tp = sum(params.values())
    for k, n in params.most_common(): print(f"  {k:24s} {n:5d}  ({100*n/tp:5.1f}%)")
    print(f"\nUNMEASURED lowercase @Composable surface: {lowercase_total} functions (NOT in the denominator above).")
    print("These are not widgets, but they are not free either — each category maps to a subsystem:")
    for k, n in lowercase.most_common():
        print(f"  {n:4d}  {k}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
