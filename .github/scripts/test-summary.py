"""Turns test results into a short table on the GitHub run page (M24).

Reads JUnit XML (Maven Surefire, Vitest) and Jest's --json output, adds up passed / failed / skipped,
and lists the failing tests by name. Writes to $GITHUB_STEP_SUMMARY on GitHub, or prints when run locally.

Never fails the job itself: the test step already did that. If no result files exist (the build broke
before any test ran), it says so instead of showing an empty table.

Usage: python .github/scripts/test-summary.py "<title>" <file or glob> [<file or glob> ...]
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def from_junit(path, totals, failures):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == 'testsuite' else root.iter('testsuite')
    for suite in suites:
        for case in suite.iter('testcase'):
            name = f"{case.get('classname', '')} > {case.get('name', '')}".strip(' >')
            if case.find('failure') is not None or case.find('error') is not None:
                totals['failed'] += 1
                failures.append(name)
            elif case.find('skipped') is not None:
                totals['skipped'] += 1
            else:
                totals['passed'] += 1


def from_jest(path, totals, failures):
    data = json.load(open(path, encoding='utf-8'))
    for suite in data.get('testResults', []):
        file = os.path.basename(suite.get('name', ''))
        for test in suite.get('assertionResults', []):
            status = test.get('status')
            if status == 'passed':
                totals['passed'] += 1
            elif status == 'failed':
                totals['failed'] += 1
                failures.append(f"{file} > {test.get('fullName', test.get('title', ''))}")
            else:
                totals['skipped'] += 1
        # A test file that crashed before running any test has no assertion results.
        if suite.get('status') == 'failed' and not suite.get('assertionResults'):
            totals['failed'] += 1
            failures.append(f"{file} (the file failed to run)")


def main():
    title, patterns = sys.argv[1], sys.argv[2:]
    files = sorted({f for p in patterns for f in glob.glob(p, recursive=True)})
    totals = {'passed': 0, 'failed': 0, 'skipped': 0}
    failures = []
    for f in files:
        (from_jest if f.endswith('.json') else from_junit)(f, totals, failures)

    lines = [f'### {title}', '']
    if not files:
        lines.append('No test results were written: the build stopped before the tests ran. See the log above.')
    else:
        mark = 'FAILED' if totals['failed'] else 'All passed'
        lines += ['| Result | Passed | Failed | Skipped |', '|---|---|---|---|',
                  f"| {mark} | {totals['passed']} | {totals['failed']} | {totals['skipped']} |"]
        if failures:
            lines += ['', 'Failing tests:'] + [f'- `{name}`' for name in failures[:50]]
            if len(failures) > 50:
                lines.append(f'- ...and {len(failures) - 50} more (see the uploaded report)')
    text = '\n'.join(lines) + '\n'

    target = os.environ.get('GITHUB_STEP_SUMMARY')
    if target:
        with open(target, 'a', encoding='utf-8') as out:
            out.write(text)
    else:
        print(text)


if __name__ == '__main__':
    main()
