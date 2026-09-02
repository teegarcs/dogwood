#!/usr/bin/env python3
"""Renders results/bridge.json as the tables that go into results/bridge.md."""
import json
import sys

data = json.load(open(sys.argv[1]))
if 'error' in data:
    print('PAGE FAILED:', data['error'])
    sys.exit(1)

print('variant:', data.get('variant'))
print(data['userAgent'])
probe = data.get('bulkCopyProbe')
if probe:
    print('bulk-copy probe ok:', probe['ok'], probe)
print('cross-origin isolated:', data['crossOriginIsolated'],
      '| cores:', data['hardwareConcurrency'],
      '| samples:', data['samples'], 'batches of >=', data['targetBatchMs'], 'ms')
print()

for p in data['payloads']:
    print(f"### {p['name']} -- {p['changes']} changes, {p['jsonBytes']} bytes "
          f"({p['jsonChars']} chars), {p['flatInts']} flat ints, "
          f"parsers agree: {p['parsersAgree']} (checksum {p['checksum']})")
    print()
    print('| Scenario | p50 us | p95 us | p99 us | max us | batch K |')
    print('|---|---:|---:|---:|---:|---:|')
    for r in p['rows']:
        print(f"| {r['label']} | {r['p50']:.3f} | {r['p95']:.3f} | "
              f"{r['p99']:.3f} | {r['max']:.3f} | {r['k']} |")
    print()
