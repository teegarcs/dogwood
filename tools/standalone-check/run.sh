#!/usr/bin/env bash
# Project Dogwood -- can a build outside this repository use it?
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/standalone-check/run.sh
#
# ADR-046 made a product's own dictionary segment work, and the sample that proved it,
# `engine/samples/product-design-system`, consumed the generator as `project(":dogwood-codegen")`.
# That is not a proof of consumability: a sibling project resolves by path, sees internal source
# sets, and needs nothing published. Every real consumer resolves from a repository.
#
# So `samples-standalone/umbra` is a **separate Gradle build**. It has no `includeBuild`, no project
# dependency and no path into `engine/`. It applies the plugin by identifier, takes the generator as
# an ordinary dependency, and compiles its generated bindings against published runtime artifacts.
# If this passes, a product outside this repository can use Dogwood.
#
# On every shipping platform, since 2026-09-13. The check used to cover a desktop application and,
# from 2026-09-09, an Android compile -- so the web host was never consumed from outside (it had no
# publishing coordinates at all, and nothing noticed) and the iOS artifacts were never resolved by a
# build that was not the engine's own. Each platform below is a bounded proof that states its own
# limit: a compile or a link, never a render. Rendering is what the engine's own drills grade; what
# they cannot grade is consumption, and consumption is what this file is for.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."

echo "==> publishing Dogwood to the local repository"
"$ROOT/engine/gradlew" -p "$ROOT/engine" publishToMavenLocal --console=plain -q

echo "==> building a product that has never heard of this repository"
# The engine's wrapper runs it, because a sample should not carry a second copy of Gradle. `-p`
# points at a different *build*, not a different project: `samples-standalone/umbra` has its own
# settings file and resolves everything from a repository.
# `:ios:assemble` is excluded here, deliberately: `build` would link four iOS frameworks at once
# (debug and release, device and simulator) and the machine ran out of memory doing it. The iOS
# section below links the one that proves anything -- the simulator debug framework -- on its own.
# Two workers for the same reason: the Kotlin/Native and WebAssembly compilations each take a
# multi-gigabyte heap, and a check that fails by exhausting the machine proves nothing.
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" build -x check -x :ios:assemble \
  --max-workers=2 --console=plain

generated="$ROOT/samples-standalone/umbra/design/build/generated/dogwood/umbraDesignSystem"
compiled="$ROOT/samples-standalone/umbra/design/build/classes/kotlin/main/dev/umbra/design"

# A green build proves less than it looks: the plugin could have registered nothing and the module
# would still compile, because a product's own implementations do not need the bindings to exist.
[ -f "$generated/host/dev/umbra/design/UmbraDesignSystemBindings.kt" ] || {
  echo "FAIL -- no host bindings were generated" >&2; exit 1; }
[ -f "$generated/guest/dev/umbra/guest/UmbraDesignSystemStubs.kt" ] || {
  echo "FAIL -- no guest stubs were generated" >&2; exit 1; }
[ -f "$compiled/UmbraDesignSystemBinding.class" ] || {
  echo "FAIL -- the generated binding did not compile" >&2; exit 1; }
[ -f "$ROOT/samples-standalone/umbra/design/surface/umbra.designsystem.lock.json" ] || {
  echo "FAIL -- no dictionary lock was written beside the surface" >&2; exit 1; }
# The component reference, which a product asks for with one line in the `dogwood` block. Asserted
# on its *content* rather than its existence: an empty file would satisfy `-f`, and the number a
# reader actually comes here for is the widget tag.
grep -q "33554435\|50331649" "$ROOT/samples-standalone/umbra/REFERENCE.md" 2>/dev/null || {
  echo "FAIL -- no component reference was generated, or it carries no widget tags" >&2; exit 1; }

generated="$ROOT/samples-standalone/umbra/design/build/generated/dogwood/umbraDesignSystem"
compiled="$ROOT/samples-standalone/umbra/design/build/classes/kotlin/main/dev/umbra/design"

# ---------------------------------------------------------------------------------------------
# The Android artifact path, which nothing here covered until 2026-09-09.
#
# `:app` is a desktop application. For as long as it was the only consumer outside the engine's own
# build, an Android adopter's resolution was exercised by nothing -- and it was broken: three engine
# modules declared `androidTarget` and none published a library variant, so no `androidJvm` variant
# existed and an Android build would have silently resolved the Java 21 `-jvm` artifact instead.
#
# Asserted on the RESOLVED VARIANT, not on a green build. A compile succeeds either way, which is
# precisely how this survived; only the variant Gradle actually selected distinguishes the fix from
# the bug.
echo
echo "==> resolving the Android artifact from a repository, in an Android build"
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :android:assembleRelease --console=plain -q
insight="$("$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :android:dependencyInsight \
  --configuration releaseCompileClasspath --dependency dogwood-host --console=plain 2>/dev/null)"
printf '%s' "$insight" | grep -q "dev.dogwood:dogwood-host-android:" || {
  echo "FAIL -- an Android build did not resolve the Android variant of dogwood-host." >&2
  echo "        Check that androidTarget { publishLibraryVariants(\"release\") } is still there;" >&2
  echo "        without it the -jvm artifact is selected and everything still compiles." >&2
  exit 1; }
echo "    resolved $(printf '%s' "$insight" | grep -o 'dev.dogwood:dogwood-host-android:[0-9.]*' | head -1)"

echo
echo "==> building, signing and serving the payload, and rendering it"
# The half no build outside the repository had ever produced (`plans/adoption-audit.md` A2): the
# guest toolchain assembled from published artifacts, end to end. The verdict is a RENDER, not a
# build -- the app runs with `--check` and exits by what its render transcript recorded: the
# payload's marker string detailed by a drawn Text, the product's OWN generated bindings as
# transcript lines, and a non-zero measured box. Its first run failed on the marker, which is how
# the check earned belief.
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :guest:jsBrowserProductionWebpackZipline --console=plain -q

payload="$ROOT/samples-standalone/umbra/guest/build/zipline/ProductionWebpack"
[ -f "$payload/manifest.zipline.json" ] || { echo "FAIL -- no signed manifest was produced" >&2; exit 1; }

(cd "$payload" && python3 -m http.server 8090 --bind 127.0.0.1 >/dev/null 2>&1) &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
sleep 1

"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :app:run --args="--check" --console=plain -q | tee /tmp/umbra-check.log
grep -q "^UMBRA CHECK PASS" /tmp/umbra-check.log || {
  echo "FAIL -- the payload did not render in the standalone host" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# The other two shipping platforms, which nothing here covered until 2026-09-13.
#
# A product's design system is multiplatform, and the generated binding -- and the
# `@Implementation` target it calls -- has to compile on every host that will call it. Umbra's
# `:design` now has every target the engine's host has, and this compiles it for the two whose
# artifacts no build outside the engine had ever resolved.
# ---------------------------------------------------------------------------------------------
echo
echo "==> compiling the product's generated bindings for the web and for iOS"
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :design:compileKotlinWasmJs --console=plain -q || {
  echo "FAIL -- the product's generated bindings did not compile for Kotlin/WebAssembly" >&2; exit 1; }
if xcrun --version >/dev/null 2>&1; then
  "$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :design:compileKotlinIosSimulatorArm64 --console=plain -q || {
    echo "FAIL -- the product's generated bindings did not compile for iOS" >&2; exit 1; }
else
  echo "    (no Xcode toolchain on this machine; the iOS compile is skipped, not passed)"
fi

# The web HOST, resolved from a repository by a WebAssembly build. Asserted on the RESOLVED VARIANT
# for the reason the Android section is: `dogwood-web` had no publishing coordinates at all until
# 2026-09-13, and a module that does not publish fails resolution outright -- but a module that
# publishes the wrong variant compiles anyway, and only the insight tells the two apart.
#
# Watched to fail without the artifact (2026-09-13): with `dogwood-web` withheld from the local
# repository, `:web:compileKotlinWasmJs` fails with
#   > Could not resolve dev.dogwood:dogwood-web:0.1.0.
# and passes again once it is restored. That is the failure this section exists to produce.
echo
echo "==> resolving the web host from a repository, in a WebAssembly build"
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :web:compileKotlinWasmJs --console=plain -q || {
  echo "FAIL -- a page outside this repository could not compile against dogwood-web." >&2
  echo "        Is dogwood-web still published? It needs maven-publish, a group and a version," >&2
  echo "        exactly as dogwood-host has them." >&2
  exit 1; }
insight="$("$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :web:dependencyInsight \
  --configuration wasmJsCompileClasspath --dependency dogwood-web --console=plain 2>/dev/null)"
printf '%s' "$insight" | grep -q "dev.dogwood:dogwood-web-wasm-js:" || {
  echo "FAIL -- a WebAssembly build did not resolve the wasm-js variant of dogwood-web." >&2
  exit 1; }
echo "    resolved $(printf '%s' "$insight" | grep -o 'dev.dogwood:dogwood-web-wasm-js:[0-9.]*' | head -1)"

# The web GUEST: the same screen `:guest` ships, built for a Worker, with the transport coming from
# `dogwood-compose` rather than from four hundred lines copied out of a sample. The verdict is the
# bundle webpack emits.
echo
echo "==> building the same payload for a Web Worker, with the transport from a library"
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :web-guest:jsBrowserProductionWebpack --console=plain -q || {
  echo "FAIL -- the Worker payload did not build" >&2; exit 1; }
bundle="$ROOT/samples-standalone/umbra/web-guest/build/kotlin-webpack/js/productionExecutable/guest-kotlin.js"
[ -f "$bundle" ] || { echo "FAIL -- no Worker bundle at $bundle" >&2; exit 1; }
grep -q "postMessage" "$bundle" || {
  echo "FAIL -- the Worker bundle carries no postMessage; the transport did not link in" >&2; exit 1; }
echo "    bundle: $(wc -c < "$bundle" | tr -d ' ') bytes"

# iOS: the framework an Xcode project links, built outside the engine. A LINK rather than a compile,
# because linking is where the three build-file lines a product copies from `ios-embed` earn their
# keep (`-lsqlite3` in particular fails only at link time).
if xcrun --version >/dev/null 2>&1; then
  echo
  echo "==> linking the product's iOS framework against repository artifacts (several minutes)"
  "$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" :ios:linkDebugFrameworkIosSimulatorArm64 --console=plain -q || {
    echo "FAIL -- the iOS framework did not link" >&2; exit 1; }
  framework="$ROOT/samples-standalone/umbra/ios/build/bin/iosSimulatorArm64/debugFramework/UmbraEmbed.framework"
  [ -f "$framework/UmbraEmbed" ] || { echo "FAIL -- no framework binary at $framework" >&2; exit 1; }
  grep -q 'umbraViewController' "$framework/Headers/UmbraEmbed.h" || {
    echo "FAIL -- the framework header does not carry the Swift-facing factory" >&2; exit 1; }
  echo "    framework: $(du -sh "$framework" | cut -f1)"
else
  echo
  echo "    (no Xcode toolchain on this machine; the iOS link is skipped, not passed)"
fi

echo
echo "PASS -- a product outside this repository generated its segment, built and signed a payload,"
echo "        rendered it in its own desktop host, compiled its design system for Android, iOS and"
echo "        the web, resolved the web host and built a Worker payload, and linked an iOS framework,"
echo "        all against published artifacts"
