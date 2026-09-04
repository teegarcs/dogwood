#!/usr/bin/env python3
"""Project Dogwood -- conformance claims that shared tests already prove.

Groups A, B, C, E and F have evidence in the ordinary test suites and had no place in the matrix,
which made the generated table look far emptier than the project actually is.

Which clients a test covers is stated per claim in `claims.tsv` rather than inferred. That is not
pedantry: `dogwood-web` compiles `dogwood-wire` and not `dogwood-host`, so a `dev.dogwood.host.*`
test says nothing about the web client, and a first draft that assumed otherwise filled twenty-one
green cells for a client that does not compile the code under test.

This maps test classes onto claims (`claims.tsv`) and reads the JUnit results the build already
writes. It asserts nothing itself -- a claim is met when the tests that are its evidence ran and
passed, and **a test that did not run at all is not a pass**: it is reported as a failure, because
"no result" and "green" must never look the same.
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

HERE = __file__.rsplit('/', 1)[0]


def load_claims(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            claim, clients, tests = line.split('\t')
            for client in clients.split(','):
                rows.append((claim, client.strip(), [t.strip() for t in tests.split(',')]))
    return rows


def test_results(roots):
    """Every test class the build reported on, and whether it was wholly green."""
    outcome = {}
    for root in roots:
        for path in glob.glob(f'{root}/**/TEST-*.xml', recursive=True) + \
                    glob.glob(f'{root}/**/*.xml', recursive=True):
            try:
                tree = ET.parse(path)
            except ET.ParseError:
                continue
            for case in tree.iter('testcase'):
                name = case.get('classname', '')
                # Kotlin multiplatform suffixes the target: `...HostileValueTest[jvm]`.
                name = re.sub(r'\[[^\]]*\]$', '', name)
                bad = case.find('failure') is not None or case.find('error') is not None
                outcome[name] = outcome.get(name, True) and not bad
    return outcome


def main(argv):
    roots = argv or [f'{HERE}/../../engine']
    claims = load_claims(f'{HERE}/claims.tsv')
    results = test_results(roots)

    by_client = defaultdict(list)
    for claim, client, tests in claims:
        missing = [t for t in tests if t not in results]
        if missing:
            by_client[client].append(
                (claim, 'FAIL', f'no result for {", ".join(missing)} -- the evidence never ran'))
        elif all(results[t] for t in tests):
            by_client[client].append((claim, 'PASS', ', '.join(t.rsplit('.', 1)[1] for t in tests)))
        else:
            red = [t.rsplit('.', 1)[1] for t in tests if not results[t]]
            by_client[client].append((claim, 'FAIL', f'{", ".join(red)} failed'))

    failures = 0
    for client, rows in sorted(by_client.items()):
        counts = defaultdict(int)
        for claim, verdict, detail in rows:
            print(f'CONF {claim} {verdict} -- {detail}')
            counts[verdict] += 1
            if verdict == 'FAIL':
                failures += 1
        print(f'CONF RESULT client={client} passed={counts["PASS"]} '
              f'failed={counts["FAIL"]} skipped=0')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
