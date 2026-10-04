import copy
import json
from pathlib import Path
import unittest

from check_integration_results import GROUPS, SCOPES, verify


class IntegrationEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"version": 2, "groups": {group: [group + "-flow"] for group in GROUPS},
                         "scopes": {scope: {"groups": list(groups), "overrides": {}} for scope, groups in SCOPES.items()}}
        self.valid = {"sha": "source", "scope": "full", "passed": True, "executed": len(GROUPS), "failed": 0, "skipped": 0,
                      "checks": [{"name": group + "-flow", "passed": True} for group in GROUPS],
                      "cleanupPassed": True, "implementedFlowsPassed": True,
                      "contractCoverage": {"matched": 1, "unmatched": []}, "deferred": ["policy pending"], "p2Complete": False}

    def test_current_complete_flows_can_pass_with_explicit_deferred_policy(self):
        verify(self.valid, "source", self.manifest)

    def test_readiness_only_report_is_rejected(self):
        report = {**self.valid, "executed": 1, "checks": [{"name": "readiness", "passed": True}]}
        with self.assertRaises(AssertionError):
            verify(report, "source", self.manifest)

    def test_missing_group_failed_cleanup_wrong_sha_and_false_completion_are_rejected(self):
        for updates in [{"cleanupPassed": False}, {"sha": "stale"}, {"p2Complete": True}, {"skipped": 1}]:
            with self.assertRaises(AssertionError):
                verify({**self.valid, **updates}, "source", self.manifest)
        manifest = copy.deepcopy(self.manifest)
        del manifest["groups"]["member"]
        with self.assertRaises(AssertionError):
            verify(self.valid, "source", manifest)

    def test_actual_scope_policy_requires_every_named_check_and_cannot_impersonate_full(self):
        manifest = json.loads(Path("scripts/integration/required-checks.json").read_text())
        for scope, policy in manifest["scopes"].items():
            names = {name for group in policy["groups"]
                     for name in policy["overrides"].get(group, manifest["groups"][group])}
            result = {**self.valid, "scope": scope, "executed": len(names),
                      "checks": [{"name": name, "passed": True} for name in names]}
            verify(result, "source", manifest, scope)
            for missing in names:
                incomplete = {**result, "executed": len(names) - 1,
                              "checks": [check for check in result["checks"] if check["name"] != missing]}
                with self.assertRaises(AssertionError):
                    verify(incomplete, "source", manifest, scope)
            if scope != "full":
                with self.assertRaises(AssertionError):
                    verify(result, "source", manifest, "full")
                with self.assertRaises(AssertionError):
                    verify({**result, "deferred": [], "p2Complete": True}, "source", manifest, scope)
        manifest["scopes"]["full"]["overrides"]["member"] = [manifest["groups"]["member"][0]]
        with self.assertRaises(AssertionError):
            verify(self.valid, "source", manifest)


if __name__ == "__main__":
    unittest.main()
