"""Select the event's comparison base, never silently compare a changed PR with itself."""
import json
import os
import re
import subprocess

event = json.load(open(os.environ["GITHUB_EVENT_PATH"]))
kind = os.environ["GITHUB_EVENT_NAME"]
if kind == "pull_request":
    base = event["pull_request"]["base"]["sha"]
elif kind == "push":
    base = event["before"]
elif kind == "workflow_dispatch":
    parent = subprocess.run(["git", "rev-parse", "--verify", "HEAD^"], text=True, capture_output=True)
    base = parent.stdout.strip() if parent.returncode == 0 else "0" * 40
else:
    raise SystemExit(f"Unsupported contract comparison event: {kind}")
if not re.fullmatch(r"[0-9a-f]{40}", base):
    raise SystemExit("Invalid contract base SHA")
if base == "0" * 40:
    base = subprocess.check_output(["git", "hash-object", "-w", "-t", "tree", "--stdin"],
                                   input="", text=True).strip()
subprocess.run(["git", "cat-file", "-e", f"{base}^{{tree}}"], check=True)
with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
    stream.write(f"sha={base}\n")
print(f"Contract comparison base: {base}")
