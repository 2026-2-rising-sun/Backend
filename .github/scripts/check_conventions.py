"""Validate GitHub naming metadata without interpreting it as shell input."""

import json
import os
import re
import sys
from pathlib import Path

KINDS = "feat|fix|refactor|test|docs|chore|build|ci|perf|revert"
ISSUE_KINDS = "FEAT|BUG|REFACTOR|TEST|DOCS|CHORE|BUILD|CI|PERF|REVERT"
SCOPES = "shopping|commerce|live|common|member|notification|infra"
DESCRIPTION = r"\S(?:[^\r\n]*\S)?"
ISSUE_TITLE = re.compile(rf"\[(?:{ISSUE_KINDS})\]\[(?:{SCOPES})\] {DESCRIPTION}")
PR_TITLE = re.compile(
    rf"(?:{KINDS})\((?:{SCOPES})\): {DESCRIPTION} \(#(?P<issue>[1-9][0-9]*)\)"
)
BRANCH = re.compile(rf"(?:{KINDS})/#(?P<issue>[1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*")
INTEGRATION_BRANCHES = {"dev", "main"}


def validate(event_name, event):
    if event_name == "issues":
        if not ISSUE_TITLE.fullmatch(event["issue"]["title"]):
            return ["Issue title must follow [FEAT][commerce] 설명 (BUG for bug reports)."]
        return []
    if event_name != "pull_request":
        return ["Only issues and pull_request events are supported."]

    pr = event["pull_request"]
    title = PR_TITLE.fullmatch(pr["title"])
    errors = []
    if not title:
        errors.append("PR title must follow feat(commerce): 설명 (#72).")

    head = pr["head"]["ref"]
    base = pr["base"]["ref"]
    # Only dev <-> main integration is exempt from feature branch naming/issue matching.
    if head in INTEGRATION_BRANCHES and base in INTEGRATION_BRANCHES and head != base:
        return errors

    branch = BRANCH.fullmatch(head)
    if not branch:
        errors.append("Head branch must follow feat/#72-order-creation (lowercase slug).")
    elif title and branch["issue"] != title["issue"]:
        errors.append("PR title issue number must match the head branch issue number.")
    return errors


def main():
    with Path(os.environ["GITHUB_EVENT_PATH"]).open(encoding="utf-8") as event_file:
        event = json.load(event_file)
    errors = validate(os.environ["GITHUB_EVENT_NAME"], event)
    for error in errors:
        print(f"::error::{error}")
    if not errors:
        print("Naming conventions passed.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
