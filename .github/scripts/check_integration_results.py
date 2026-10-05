"""Verify A5 evidence is non-empty, complete and belongs to this exact checkout."""
import json
from pathlib import Path
import subprocess

GROUPS = {"member", "seller", "cart_order_payment", "ownership", "service_callers", "dependencies", "live", "database", "mock_failures", "sessions"}


def verify(result, sha, manifest):
    assert result["sha"] == sha, "Integration evidence belongs to another commit"
    assert result["passed"] is True, "Integration run failed"
    assert type(result["executed"]) is int and result["executed"] > 0, "No integration checks executed"
    assert all(type(result[k]) is int and result[k] == 0 for k in ("failed", "skipped")), "Checks failed or skipped"
    checks = result["checks"]
    assert len(checks) == result["executed"], "Integration check count is inconsistent"
    assert all(isinstance(c.get("name"), str) and c["name"] and c.get("passed") is True for c in checks), "Incomplete evidence"
    names = {c["name"] for c in checks}
    assert len(names) == len(checks), "Duplicate integration check names"
    assert manifest["version"] == 1 and set(manifest["groups"]) == GROUPS, "Required scenario groups changed or disappeared"
    for group, required in manifest["groups"].items():
        assert required and len(required) == len(set(required)), f"Invalid required checks: {group}"
        assert set(required) <= names, f"Missing {group} checks: {sorted(set(required) - names)}"
    assert result["cleanupPassed"] is True and result["implementedFlowsPassed"] is True, "Run or cleanup incomplete"
    assert result["contractCoverage"]["matched"] > 0 and not result["contractCoverage"]["unmatched"], "Response contract coverage incomplete"
    assert isinstance(result["deferred"], list), "Policy/deferred scope must be explicit"
    assert result["p2Complete"] is (not result["deferred"]), "P2 completion must distinguish deferred policy work"


if __name__ == "__main__":
    result = json.loads(Path("build/integration/summary.json").read_text())
    manifest = json.loads(Path("scripts/integration/required-checks.json").read_text())
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    verify(result, sha, manifest)
    print(json.dumps({k: result[k] for k in ("sha", "passed", "executed", "failed", "skipped", "deferred", "p2Complete")}, indent=2))
