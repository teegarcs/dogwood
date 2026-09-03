#!/usr/bin/env bash
# Project Dogwood -- the Phase 5 page-weight gate, measured.
#
# Reports the bytes a first-time visitor downloads, per configuration, raw and compressed.
# Run after:
#   ./gradlew :floor:wasmJsBrowserDistribution :material:wasmJsBrowserDistribution
set -euo pipefail
cd "$(dirname "$0")"

for m in floor material; do
  d="$m/build/dist/wasmJs/productionExecutable"
  [ -d "$d" ] || { echo "$m: not built"; continue; }
  echo "=== $m ==="
  tot=0; gz=0; br=0
  # The source map is excluded deliberately: browsers fetch it only when developer tools are open,
  # so counting it would overstate what a user downloads.
  for f in "$d"/*.wasm "$d"/app.js "$d"/index.html; do
    [ -f "$f" ] || continue
    s=$(stat -f%z "$f")
    g=$(gzip -9 -c "$f" | wc -c | tr -d ' ')
    b=$(brotli -q 11 -c "$f" | wc -c | tr -d ' ')
    printf "  %-30s raw %9s  gzip %9s  brotli %9s\n" "$(basename "$f")" "$s" "$g" "$b"
    tot=$((tot+s)); gz=$((gz+g)); br=$((br+b))
  done
  printf "  %-30s raw %9s  gzip %9s  brotli %9s\n" "TOTAL" "$tot" "$gz" "$br"
  printf "  %-30s   %8.2f MB   %8.2f MB     %8.2f MB\n" "" \
    "$(echo "scale=4;$tot/1048576"|bc)" "$(echo "scale=4;$gz/1048576"|bc)" "$(echo "scale=4;$br/1048576"|bc)"
done
