import copy
import unittest

from check_integration_results import GROUPS, verify


class IntegrationEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"version": 1, "groups": {group: [group + "-flow"] for group in GROUPS}}
        self.valid = {"sha": "source", "passed": True, "executed": len(GROUPS), "failed": 0, "skipped": 0,
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


if __name__ == "__main__":
    unittest.main()
