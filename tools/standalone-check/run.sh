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
"$ROOT/engine/gradlew" -p "$ROOT/samples-standalone/umbra" build --console=plain

generated="$ROOT/samples-standalone/umbra/build/generated/dogwood/umbraDesignSystem"
compiled="$ROOT/samples-standalone/umbra/build/classes/kotlin/main/dev/umbra/design"

# A green build proves less than it looks: the plugin could have registered nothing and the module
# would still compile, because a product's own implementations do not need the bindings to exist.
[ -f "$generated/host/dev/umbra/design/UmbraDesignSystemBindings.kt" ] || {
  echo "FAIL -- no host bindings were generated" >&2; exit 1; }
[ -f "$generated/guest/dev/umbra/guest/UmbraDesignSystemStubs.kt" ] || {
  echo "FAIL -- no guest stubs were generated" >&2; exit 1; }
[ -f "$compiled/UmbraDesignSystemBinding.class" ] || {
  echo "FAIL -- the generated binding did not compile" >&2; exit 1; }
[ -f "$ROOT/samples-standalone/umbra/surface/umbra.designsystem.lock.json" ] || {
  echo "FAIL -- no dictionary lock was written beside the surface" >&2; exit 1; }
# The component reference, which a product asks for with one line in the `dogwood` block. Asserted
# on its *content* rather than its existence: an empty file would satisfy `-f`, and the number a
# reader actually comes here for is the widget tag.
grep -q "33554435\|50331649" "$ROOT/samples-standalone/umbra/REFERENCE.md" 2>/dev/null || {
  echo "FAIL -- no component reference was generated, or it carries no widget tags" >&2; exit 1; }

echo
echo "PASS -- a build outside this repository generated, compiled and locked its own segment"
