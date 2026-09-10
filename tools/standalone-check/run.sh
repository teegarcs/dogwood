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
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."

echo "==> publishing Dogwood to the local repository"
"$ROOT/engine/gradlew" -p "$ROOT/engine" publishToMavenLocal --console=plain -q

echo "==> building a product that has never heard of this repository"
# The engine's wrapper runs it, because a sample should not carry a second copy of Gradle. `-p`
# points at a different *build*, not a different project: `samples-standalone/umbra` has its own
# settings file and resolves everything from a repository.
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" build -x check --console=plain

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

echo
echo "PASS -- a product outside this repository generated its segment, built and signed a payload,"
echo "        and rendered it in its own host application, all against published artifacts"
