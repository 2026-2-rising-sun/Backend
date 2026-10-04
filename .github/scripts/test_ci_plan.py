"""Regressions for missed changes and incorrectly accepted verification skips."""
import copy
from pathlib import Path
import subprocess
import tempfile
import unittest

from ci_gate import verify
from ci_plan import changed_paths, parse_changes, plan_changes, SERVICES, HTTP_SERVICES


class PlannerTest(unittest.TestCase):
    def test_document_and_issue_form_only_skip_heavy_jobs(self):
        plan = plan_changes(["README.md", "services/member-service/README.md", "docs/a.txt",
                             "contracts/docs/decision.adoc", ".github/ISSUE_TEMPLATE/bug.yml"])
        self.assertFalse(any(plan["jobs"].values()))
        self.assertEqual(plan["tasks"], [])
        self.assertEqual(plan["postgres_cases"], [])

    def test_service_changes_run_only_their_build_and_database_suite(self):
        for service in SERVICES:
            with self.subTest(service=service):
                plan = plan_changes([f"services/{service}-service/src/main/java/Changed.java"])
                self.assertEqual(plan["services"], [service])
                self.assertIn(f":services:{service}-service:build", plan["tasks"])
                self.assertFalse(plan["jobs"]["contracts"])
                self.assertEqual(plan["postgres"], service in {"member", "commerce", "live"})
                self.assertEqual(plan["jobs"]["integration"], service in HTTP_SERVICES)
                if plan["postgres"]:
                    self.assertEqual([entry["service"] for entry in plan["postgres_cases"]], [service])
                self.assertFalse(any(task.endswith(":bootJar") for task in plan["tasks"]))

    def test_migration_is_executable_even_if_size_label_ignores_it(self):
        plan = plan_changes(["services/commerce-service/src/main/resources/db/migration/V4__change.sql"])
        self.assertEqual(plan["services"], ["commerce"])
        self.assertTrue(plan["postgres"])
        self.assertTrue(plan["jobs"]["integration"])

    def test_api_change_keeps_real_response_contract_verification(self):
        plan = plan_changes(["contracts/api/shopping-service.yaml"])
        self.assertEqual(plan["jobs"], dict(build=False, contracts=True, integration=True))
        self.assertEqual(plan["services"], [])
        self.assertEqual(plan["tasks"], [])
        self.assertEqual(plan["contract_services"], ["shopping"])
        self.assertEqual(plan["integration_scope"], "shopping")

    def test_domain_scopes_and_test_only_changes(self):
        for service in HTTP_SERVICES:
            plan = plan_changes([f"services/{service}-service/src/main/java/Changed.java"])
            self.assertEqual(plan["integration_scope"], "full" if service == "member" else service)
            tests = plan_changes([f"services/{service}-service/src/test/java/ChangedTest.java"])
            self.assertFalse(tests["jobs"]["integration"])
            self.assertEqual(tests["tasks"][0], f":services:{service}-service:build")
        mixed = plan_changes(["contracts/api/commerce-service.yaml", "services/live-service/src/main/java/Changed.java"])
        self.assertEqual(mixed["integration_scope"], "full")
        renamed = plan_changes(["services/commerce-service/src/main/java/Old.java", "services/commerce-service/src/test/java/New.java"])
        self.assertTrue(renamed["jobs"]["integration"])
        self.assertTrue(plan_changes(["contracts/api/unknown.yaml"])["full"])

    def test_shared_scenarios_expand_prism_but_do_not_need_applications(self):
        plan = plan_changes(["contracts/api/commerce-service.yaml", "contracts/scenarios/prism.json"])
        self.assertEqual(plan["contract_services"], list(HTTP_SERVICES))
        self.assertEqual(plan["integration_scope"], "commerce")
        self.assertEqual(plan_changes(["contracts/scenarios/prism.json"])["contract_services"], list(HTTP_SERVICES))

    def test_prism_and_review_metadata_need_only_contract_job(self):
        plan = plan_changes(["contracts/scenarios/prism.json", "contracts/docs/change-review.json"])
        self.assertEqual(plan["jobs"], dict(build=False, contracts=True, integration=False))

    def test_shared_and_unknown_executable_changes_fail_closed_to_full(self):
        for path in ["libs/common-core/src/main/java/Core.java", "contracts/events/src/main/java/Event.java",
                     "build-logic/plugin.gradle.kts", "gradle/libs.versions.toml", "settings.gradle.kts",
                     ".github/workflows/ci.yml", "new-tool.sh", "application.yaml",
                     "services/member/config.ini", ".github/ISSUE_TEMPLATE/unexpected.py"]:
            with self.subTest(path=path):
                plan = plan_changes([path])
                self.assertTrue(plan["full"])
                self.assertTrue(all(plan["jobs"].values()))
                self.assertEqual(plan["tasks"], ["build", ":services:member-service:postgresTest", ":services:commerce-service:postgresCommerceTest"])

    def test_push_and_manual_full_plan_ignores_doc_only_scope(self):
        plan = plan_changes(["README.md"], full=True)
        self.assertEqual(plan["services"], list(SERVICES))
        self.assertTrue(all(plan["jobs"].values()))

    def test_scripts_cannot_skip_the_verification_they_implement(self):
        for path in ["scripts/contracts/lib.cjs", "scripts/local/contracts.cjs", "scripts/integration/run.cjs"]:
            self.assertTrue(plan_changes([path])["jobs"]["integration"])

    def test_nul_parser_retains_deleted_and_both_renamed_paths(self):
        self.assertEqual(parse_changes(b"R100\0services/live-service/a\n.java\0docs/a\t.md\0D\0old.sql\0"),
                         ["services/live-service/a\n.java", "docs/a\t.md", "old.sql"])
        for raw in [b"M\0truncated", b"R100\0one\0", b"garbage\0name\0", b"M\0../escape\0"]:
            with self.assertRaises(ValueError):
                parse_changes(raw)

    def test_real_git_diff_uses_merge_base_and_handles_rename_delete_and_newlines(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], stderr=subprocess.DEVNULL).decode().strip()
            git("init", "-b", "base")
            git("config", "user.name", "CI test")
            git("config", "user.email", "ci-test@example.invalid")
            source = Path(directory, "services/live-service/src/main/java/old\nname.java")
            source.parent.mkdir(parents=True)
            source.write_text("unchanged rename contents\n")
            Path(directory, "deleted.sql").write_text("select 1;\n")
            git("add", ".")
            git("commit", "-m", "base")
            git("branch", "head")
            Path(directory, "unrelated.sh").write_text("base branch only\n")
            git("add", ".")
            git("commit", "-m", "advance base")
            base = git("rev-parse", "HEAD")
            git("checkout", "head")
            destination = Path(directory, "docs/new\tname.md")
            destination.parent.mkdir()
            source.rename(destination)
            Path(directory, "deleted.sql").unlink()
            git("add", "-A")
            git("commit", "-m", "rename and delete")
            head = git("rev-parse", "HEAD")
            # The production command runs at the repository root; preserve this test's CWD.
            import os
            previous = os.getcwd()
            try:
                os.chdir(directory)
                paths = changed_paths(base, head)
            finally:
                os.chdir(previous)
            self.assertIn("services/live-service/src/main/java/old\nname.java", paths)
            self.assertIn("docs/new\tname.md", paths)
            self.assertIn("deleted.sql", paths)
            self.assertNotIn("unrelated.sh", paths)


class GateTest(unittest.TestCase):
    def evidence(self, plan):
        return dict(plan=dict(result="success"), **{
            job: dict(result="success" if enabled else "skipped") for job, enabled in plan["jobs"].items()})

    def test_only_intended_skips_pass(self):
        for paths in [["README.md"], ["services/notification-service/src/main/java/Changed.java"],
                      ["contracts/api/member-service.yaml"], ["settings.gradle.kts"]]:
            plan = plan_changes(paths)
            verify(plan, self.evidence(plan))

    def test_required_failure_cancel_or_skip_cannot_pass(self):
        plan = plan_changes(["services/commerce-service/src/main/java/Changed.java"])
        for job in ["plan", "build", "integration"]:
            for state in ["failure", "cancelled", "skipped"]:
                evidence = self.evidence(plan)
                evidence[job]["result"] = state
                with self.assertRaises(ValueError):
                    verify(plan, evidence)

    def test_unplanned_success_missing_jobs_and_invalid_flags_cannot_pass(self):
        plan = plan_changes(["README.md"])
        evidence = self.evidence(plan)
        evidence["build"]["result"] = "success"
        with self.assertRaises(ValueError):
            verify(plan, evidence)
        evidence = self.evidence(plan)
        del evidence["build"]
        with self.assertRaises(ValueError):
            verify(plan, evidence)
        invalid = copy.deepcopy(plan)
        invalid["jobs"]["build"] = "false"
        with self.assertRaises(ValueError):
            verify(invalid, self.evidence(plan))
        invalid = copy.deepcopy(plan)
        invalid["full"] = True
        with self.assertRaises(ValueError):
            verify(invalid, self.evidence(plan))
