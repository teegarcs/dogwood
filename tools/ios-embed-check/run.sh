#!/usr/bin/env bash
# Project Dogwood -- an existing iOS application can actually embed this.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/ios-embed-check/run.sh
#
# The adoption audit's B4 was that ADR-004 described a product "building a framework and embedding
# it" while nothing built one -- so the claim was prose. This assembles the real XCFramework and
# asserts on what an Xcode project would actually receive: both slices, and a header carrying the
# Swift-visible factory with the types it takes exported alongside it.
#
# Header inspection rather than a compiled Swift app, and the limit is stated rather than hidden: a
# real Xcode project would prove the link too. What this catches is the failure mode that actually
# occurs -- an `export(...)` dropped from the build file, which compiles fine and produces a header
# whose factory takes types the consumer cannot name.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
pass=0; fail=0
check() { if [ "$2" = "1" ]; then pass=$((pass+1)); echo "  ok   $1"; else fail=$((fail+1)); echo "  FAIL $1 -- $3" >&2; fi; }

echo "==> assembling the XCFramework (three Kotlin/Native links; several minutes)"
"$ROOT/engine/gradlew" -p "$ROOT/engine" :samples:ios-embed:assembleDogwoodEmbedXCFramework --console=plain -q || {
  echo "FAIL -- the XCFramework did not assemble" >&2; exit 1; }

XC="$ROOT/engine/samples/ios-embed/build/XCFrameworks/release/DogwoodEmbed.xcframework"
[ -d "$XC" ] || XC="$ROOT/engine/samples/ios-embed/build/XCFrameworks/debug/DogwoodEmbed.xcframework"

check "the XCFramework exists" "$([ -d "$XC" ] && echo 1 || echo 0)" "no bundle at $XC"
# Device AND simulator: three separate frameworks would make the consuming project choose, and
# choosing wrong fails at link time on a machine that is not the one that chose.
check "it carries a device slice" "$([ -d "$XC/ios-arm64" ] && echo 1 || echo 0)" "no ios-arm64"
check "it carries a simulator slice" \
  "$(ls -d "$XC"/ios-*simulator >/dev/null 2>&1 && echo 1 || echo 0)" "no simulator slice"

HEADER="$(find "$XC" -name 'DogwoodEmbed.h' | head -1)"
check "it has a header" "$([ -n "$HEADER" ] && echo 1 || echo 0)" "no generated header"
if [ -n "$HEADER" ]; then
  # The factory, by its SWIFT name: the Objective-C symbol can survive while the Swift-facing
  # signature changes underneath it, and Swift is what a product writes.
  check "Swift sees the factory" \
    "$(grep -c 'swift_name("dogwoodViewController(manifestUrl:entryPoint:trustedKeys:launchParams:onFailure:)")' "$HEADER" | head -1 | awk '{print ($1>0)?1:0}')" \
    "the factory is not exported under its documented Swift signature"
  # And the exported dependency: without `export(project(":dogwood-host"))` the framework still
  # builds and the header still declares the factory -- with parameter types nothing else declares.
  check "host types are exported with it" \
    "$(grep -c 'DogwoodExperience\|DogwoodSurface' "$HEADER" | awk '{print ($1>0)?1:0}')" \
    "dogwood-host was compiled in but not exported"
fi

echo
if [ "$fail" = "0" ]; then echo "PASS -- $pass properties of the embeddable framework"; else echo "FAIL -- $fail" >&2; exit 1; fi
