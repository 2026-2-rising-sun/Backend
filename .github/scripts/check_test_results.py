"""Fail closed on missing, empty, failed or unexpectedly skipped Gradle JUnit reports."""
import argparse
import fnmatch
import glob
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def inspect_reports(patterns, required=(), allowed_skips=None, minimums=None):
    allowed_skips, minimums = allowed_skips or {}, minimums or {}
    files, problems, suites = set(), [], []
    for pattern in patterns:
        matches = glob.glob(pattern, recursive=True)
        if not matches:
            problems.append(f"Missing reports: {pattern}")
        files.update(matches)
    for filename in sorted(files):
        root = ET.parse(filename).getroot()
        name = root.get("name", "")
        cases = root.findall("testcase")
        classes = {c.get("classname", name) for c in cases}
        if len(classes) == 1:
            name = next(iter(classes))  # Gradle's suite name may be a translated @DisplayName.
        elif len(classes) > 1:
            problems.append(f"Unexpected mixed-class Gradle report: {filename}")
        counts = dict(tests=len(cases), skipped=sum(c.find("skipped") is not None for c in cases),
                      failures=sum(c.find("failure") is not None for c in cases),
                      errors=sum(c.find("error") is not None for c in cases))
        if root.tag != "testsuite" or any(int(root.get(k, "-1")) != v for k, v in counts.items()):
            problems.append(f"Invalid or inconsistent JUnit counts: {filename}")
        if counts["tests"] == 0 or counts["failures"] or counts["errors"]:
            problems.append(f"Empty or failed suite: {name}")
        if counts["skipped"] and counts["skipped"] != allowed_skips.get(name, 0):
            problems.append(f"Unexpected skipped tests: {name}")
        suites.append(dict(name=name, file=filename, **counts))
    for pattern in required:
        if not any(fnmatch.fnmatchcase(s["name"], pattern) for s in suites):
            problems.append(f"Required suite did not execute: {pattern}")
    for pattern, minimum in minimums.items():
        executed = sum(s["tests"] - s["skipped"] for s in suites if fnmatch.fnmatchcase(s["name"], pattern))
        if executed < minimum:
            problems.append(f"Expected at least {minimum} executed tests in {pattern}, got {executed}")
    totals = {key: sum(s[key] for s in suites) for key in ("tests", "skipped", "failures", "errors")}
    if totals["tests"] - totals["skipped"] <= 0:
        problems.append("No tests executed")
    return dict(passed=not problems, **totals, problems=problems, suites=suites)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+")
    parser.add_argument("--require-suite", action="append", default=[])
    parser.add_argument("--allow-skipped-suite", action="append", default=[])
    parser.add_argument("--min-suite-tests", action="append", default=[])
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    def counts(values):
        return {name: int(count) for name, count in (value.rsplit("=", 1) for value in values)}
    result = inspect_reports(args.reports, args.require_suite, counts(args.allow_skipped_suite),
                             counts(args.min_suite_tests))
    result["sha"] = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if os.getenv("GITHUB_SHA") and result["sha"] != os.environ["GITHUB_SHA"]:
        result["problems"].append("Checkout differs from the CI commit")
        result["passed"] = False
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n")
    summary = {k: v for k, v in result.items() if k != "suites"}
    print(json.dumps(summary, indent=2))
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(f"### {output.stem}\n```json\n{json.dumps(summary, indent=2)}\n```\n")
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
