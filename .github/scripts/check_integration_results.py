"""Verify A5 evidence is non-empty, complete and belongs to this exact checkout."""
import json
from pathlib import Path
import subprocess

path = Path("build/integration/summary.json")
result = json.loads(path.read_text())
sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
assert result["sha"] == sha, "Integration evidence belongs to another commit"
assert result["passed"] is True, "Integration run failed"
assert type(result["executed"]) is int and result["executed"] > 0, "No integration checks executed"
assert result["failed"] == 0 and result["skipped"] == 0, "Integration checks failed or skipped"
checks = result["checks"]
assert len(checks) == result["executed"], "Integration check count is inconsistent"
assert all(c.get("name") and c.get("passed") is True for c in checks), "Incomplete integration evidence"
assert len({c["name"] for c in checks}) == len(checks), "Duplicate integration check names"
print(json.dumps({k: result[k] for k in ("sha", "passed", "executed", "failed", "skipped")}, indent=2))
