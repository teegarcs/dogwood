#!/usr/bin/env python3
"""Decides whether a run proved what it claims to prove.

Exists because a harness that prints a report and exits zero has verified nothing. Every assertion
here corresponds to a sentence in ADR-032 that would otherwise be taken on trust.
"""
import json
import sys

mode, path = sys.argv[1], sys.argv[2]
report = json.load(open(path))
failures = []


def require(condition, message):
    if not condition:
        failures.append(message)


def show(name):
    value = report.get(name)
    if value is not None:
        print(f'  {name}: {value}')


print(f'== {mode} ==')
for key in ('gate', 'manifest', 'workerCreated', 'refused', 'firstFrameMs', 'appliedBatches',
            'renderedNodes', 'snapshot'):
    show(key)
print('  server:', report.get('server'))
print('  harness:', report.get('harness'))
if report.get('log'):
    print('  log:')
    for line in report['log']:
        print(f'    - {line}')
for key in ('treeAfterFirstBatch', 'treeAfterEvent', 'transcript'):
    if report.get(key):
        print(f'  {key}:')
        for line in report[key].splitlines():
            print(f'    | {line}')

# The correctness gate ADR-032 requires. Both runs assert it: it is the shipped binary checking
# that `--gufa` removal actually took, and it must hold whatever the page then goes on to do.
require(report.get('gate') == 'pass',
        'the bulk-copy correctness gate did not pass; see ADR-032 on --gufa')

if mode == 'render':
    require(report.get('workerCreated') == 'true', 'the Worker was not created')
    require(report.get('server', {}).get('guestScriptExecuted') is True,
            'the guest script never ran')
    require(int(report.get('appliedBatches', 0)) >= 2,
            'fewer than two batches were applied, so the event round trip did not complete')
    tree = report.get('treeAfterFirstBatch', '')
    require('Text#2' in tree and 'Dogwood on the web' in tree,
            "the guest's title never reached the host tree")
    require('Column#1' in tree and 'Row#4' in tree and 'Box#6' in tree,
            'the container tree did not arrive intact')
    require('taps: 0' in tree, 'the counter did not arrive at its initial value')
    require('taps: 1' in report.get('treeAfterEvent', ''),
            'the event did not produce an updated batch')
    transcript = report.get('transcript', '')
    require('Text#2 "Dogwood on the web"' in transcript,
            'the Text binding never composed with the guest\'s string')
    measured = [line for line in transcript.splitlines()
                if line.startswith('Text#2 measured') and not line.endswith('0x0')]
    require(bool(measured),
            'Compose never measured a non-zero box for the title, so nothing was laid out')
    require(report.get('snapshot') not in (None, '(never answered)'),
            'the correlated snapshotState request was never answered')
    require(report.get('snapshot', '').find('taps') >= 0,
            'the snapshot did not carry the guest\'s state')
    # The guest asks for one frame on correlation 2, and the host answers it from inside its own
    # composition on a real display frame. Asserting the identifier as well as the count is what
    # separates a correlated bridge from one that merely delivers something.
    require('framesReceived=[1]' in report.get('snapshot', ''),
            f"the guest never received the frame it asked for: {report.get('snapshot')}")
    require('lastFrameCorrelation=[2]' in report.get('snapshot', ''),
            f"the frame came back on the wrong correlation: {report.get('snapshot')}")
    require(report.get('firstFrameMs') is not None, 'Compose never produced a frame')
    require(report.get('harness', {}).get('timedOut') is False, 'the page timed out')
    require(report.get('harness', {}).get('canvas', 'none') != 'none',
            'Compose Multiplatform never created a canvas')

if mode == 'refusal':
    require(report.get('workerCreated') == 'false', 'a Worker was created for a refused manifest')
    require(report.get('refused') == 'DictionarySkew',
            f"expected a dictionary-skew refusal, got {report.get('refused')}")
    require(report.get('server', {}).get('guestScriptFetched') is False,
            'the guest script was fetched despite the refusal')
    require(report.get('server', {}).get('guestScriptExecuted') is False,
            'the guest script RAN despite the refusal -- the check did not run first')

if failures:
    print()
    for failure in failures:
        print(f'  FAIL: {failure}')
    sys.exit(1)
print('  all assertions passed')
