"""로컬 변경 수집과 실제 검사 결과의 구분을 검증한다."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import verify


class ChangeCollectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "검증")
        self.git("config", "user.email", "verify@example.invalid")
        self.write(".gitignore", "build/\nignored/\n")
        self.write("README.md", "기준\n")
        self.git("add", ".")
        self.git("commit", "-qm", "검증 기준")

    def git(self, *args):
        return verify.git(self.root, *args)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
        return file

    def changes(self, base=None):
        return verify.collect_changes(self.root, base)[2]

    def test_collects_staged_unstaged_and_untracked_but_not_ignored(self):
        """스테이지·작업 트리·새 파일을 포함하고 무시한 파일은 제외한다."""
        self.write("README.md", "수정\n")
        self.git("add", "README.md")
        self.write("README.md", "추가 수정\n")
        self.write("새 파일.md", "새 문서\n")
        self.write("ignored/secret.txt", "로그에 없어야 하는 값")
        changes = self.changes()
        self.assertEqual({c.path for c in changes}, {"README.md", "새 파일.md"})
        self.assertEqual({c.after for c in changes if c.path == "README.md"},
                         {"수정\n".encode(), "추가 수정\n".encode()})

    def test_selects_staged_code_even_when_worktree_restores_head(self):
        """작업 트리가 HEAD와 같아도 스테이지의 실행 코드 변경을 검사한다."""
        path = "services/notification-service/src/main/java/Example.java"
        self.write(path, "class Example {}")
        self.git("add", ".")
        self.git("commit", "-qm", "코드 기준")
        self.write(path, "class Example { int value = 1; }")
        self.git("add", path)
        self.write(path, "class Example {}")
        plan = verify.CI.classify(self.changes())
        self.assertEqual(plan["gradle_tasks"], [":services:notification-service:build"])

    def test_move_selects_both_services_and_deletion_is_preserved(self):
        """서비스 간 이동을 삭제·추가로 보아 양쪽 검사를 선택한다."""
        old = "services/member-service/src/main/java/Example.java"
        new = "services/notification-service/src/main/java/Example.java"
        self.write(old, "class Example {}")
        self.git("add", ".")
        self.git("commit", "-qm", "이동 기준")
        (self.root / old).unlink()
        self.write(new, "class Example {}")
        changes = self.changes()
        self.assertIsNone(next(c.after for c in changes if c.path == old))
        self.assertEqual(verify.CI.classify(changes)["gradle_tasks"],
                         [":services:member-service:build", ":services:notification-service:build"])

    def test_base_includes_committed_changes_and_uses_merge_base(self):
        """기준 브랜치 이후 커밋도 PR 검증 범위에 포함한다."""
        self.git("branch", "baseline")
        self.write("services/member-service/src/main/java/Example.java", "class Example {}")
        self.git("add", ".")
        self.git("commit", "-qm", "기능 변경")
        self.assertEqual(self.changes(), [])
        self.assertIn("services/member-service/src/main/java/Example.java",
                      {c.path for c in self.changes("baseline")})
        with self.assertRaises(subprocess.CalledProcessError):
            self.changes("missing-ref")

    def test_mode_change_does_not_become_comments_only(self):
        """실행 권한 변경을 주석만 바뀐 것으로 분류하지 않는다."""
        path = "services/member-service/src/main/java/Example.java"
        file = self.write(path, "class Example {}")
        self.git("add", ".")
        self.git("commit", "-qm", "권한 기준")
        file.chmod(0o755)
        self.assertFalse(self.changes()[0].same_mode)

    def test_symlink_reads_link_text_without_reading_target(self):
        """심볼릭 링크의 외부 대상 내용을 읽지 않는다."""
        file = self.root / "link.md"
        file.symlink_to(self.root / "missing-target")
        change = next(c for c in self.changes() if c.path == "link.md")
        self.assertEqual(change.after, os.fsencode(os.readlink(file)))

    def test_dry_run_writes_nothing_and_runs_no_checks(self):
        """dry-run은 Git 상태와 파일을 바꾸거나 검사를 실행하지 않는다."""
        self.write("README.md", "수정")
        before = self.git("status", "--porcelain=v1", "-z")
        with patch.object(verify, "run_checks") as run, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(verify.main(["--root", str(self.root), "--dry-run"]), 0)
        run.assert_not_called()
        self.assertEqual(self.git("status", "--porcelain=v1", "-z"), before)
        self.assertFalse((self.root / "build").exists())

    def test_no_changes_is_not_a_test_success_claim(self):
        """변경 없음은 검사 실행 없이 종료한다."""
        output = io.StringIO()
        with patch.object(verify, "run_checks") as run, contextlib.redirect_stdout(output):
            self.assertEqual(verify.main(["--root", str(self.root)]), 0)
        run.assert_not_called()
        self.assertIn("변경 없음", output.getvalue())
        report = json.loads((self.root / "build/harness/result.json").read_text())
        self.assertEqual(report["status"], "no-changes")

    def test_conflict_stops_before_checks(self):
        """충돌 상태에서는 검사 선택을 진행하지 않는다."""
        self.git("checkout", "-qb", "other")
        self.write("README.md", "다른 변경")
        self.git("commit", "-qam", "다른 변경")
        self.git("checkout", "-q", "-")
        self.write("README.md", "현재 변경")
        self.git("commit", "-qam", "현재 변경")
        subprocess.run(["git", "-C", str(self.root), "merge", "other"], capture_output=True)
        with self.assertRaisesRegex(ValueError, "충돌"):
            self.changes()

    def test_changed_during_checks_is_incomplete(self):
        """검사 도중 소스가 바뀌면 결과를 전체 성공으로 쓰지 않는다."""
        self.write("README.md", "검사 전")
        def mutate(*args):
            self.write("README.md", "검사 후")
            return []
        with patch.object(verify, "run_checks", side_effect=mutate), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(verify.main(["--root", str(self.root)]), 2)
        report = json.loads((self.root / "build/harness/result.json").read_text())
        self.assertTrue(report["changed_during_checks"])


class SelectionTests(unittest.TestCase):
    def test_service_comment_api_and_common_reuse_ci_selection(self):
        """서비스·주석·계약·공통 변경의 기존 선택 결과를 재사용한다."""
        cases = [
            (verify.CI.Change("README.md", b"old", b"new"), []),
            (verify.CI.Change("services/member-service/src/main/java/A.java", b"class A {}", b"// comment\nclass A {}"),
             [":services:member-service:spotlessCheck"]),
            (verify.CI.Change("services/member-service/src/main/java/A.java", None, b"class A {}"),
             [":services:member-service:build"]),
            (verify.CI.Change("common-module/src/main/java/A.java", None, b"class A {}"), ["build"]),
        ]
        for change, expected in cases:
            with self.subTest(path=change.path):
                plan = verify.CI.classify([change])
                commands = verify.commands_for(plan)
                self.assertEqual(plan["gradle_tasks"], expected)
                self.assertEqual(any(c["name"] == "gradle" for c in commands), bool(expected))
        plan = verify.CI.classify([verify.CI.Change("contracts/api/member-service.yaml", None, b"paths: {}")])
        self.assertIn("api", [c["name"] for c in verify.commands_for(plan)])

    def test_all_tool_flags_and_external_tasks_have_commands(self):
        """기존 분류기의 도구·외부 검사·표본 검사가 빠지지 않는다."""
        plan = verify.CI.classify([], "비교 불가")
        checks = verify.commands_for(plan)
        names = {c["name"] for c in checks}
        self.assertTrue({"classifier", "api_tests", "harness", "workflow", "local-auth",
                         "api", "events", "gradle", "event-samples"} <= names)
        self.assertTrue(set(plan["migration_tasks"] + plan["redis_tasks"]) <= names)
        self.assertLess([c["name"] for c in checks].index("gradle"),
                        [c["name"] for c in checks].index("event-samples"))

    def test_runner_changes_select_own_tests_without_java_build(self):
        """실행기 변경은 자체 테스트를 선택하고 업무 빌드를 추가하지 않는다."""
        plan = verify.CI.classify([verify.CI.Change("harness/verify.py", None, b"code")])
        self.assertTrue(plan["harness"])
        self.assertEqual(plan["gradle_tasks"], [])

    def test_external_connection_settings_follow_existing_tasks(self):
        """외부 작업의 서비스별 연결 선택 조건을 구분한다."""
        self.assertTrue(verify.external_ready(":services:member-service:migrationTest",
                                            {"MEMBER_TEST_DB_URL": "jdbc:postgresql://localhost/test"}))
        self.assertFalse(verify.external_ready(":services:member-service:migrationTest", {}))
        self.assertTrue(verify.external_ready(":services:live-service:migrationTest", {"LIVE_PG_TEST": "1"}))
        self.assertTrue(verify.external_ready(":services:live-service:redisTest", {"LIVE_REDIS_TEST": "1"}))


class ExecutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def check(self, name, code, external=None):
        return {"name": name, "command": [sys.executable, "-c", code], "external": external}

    def run_checks(self, checks, external=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return verify.run_checks(self.root, checks, external)

    def test_exit_status_and_missing_command_are_distinct(self):
        """명령 성공·실패·실행 불가를 구분하고 로그를 남긴다."""
        checks = [self.check("pass", "print('검사 완료')"), self.check("fail", "raise SystemExit(3)"),
                  {"name": "missing", "command": ["/no-such-harness-command"], "external": None}]
        results = self.run_checks(checks)
        self.assertEqual([r["status"] for r in results], ["passed", "failed", "not-run"])
        self.assertIn("검사 완료", (self.root / results[0]["log"]).read_text())

    def test_external_checks_require_explicit_opt_in_and_environment(self):
        """외부 검사는 명시 선택과 환경 조건이 모두 있어야 실행한다."""
        task = ":services:live-service:redisTest"
        check = self.check(task, "print('연결 조건 검사')", task)
        with patch.dict(os.environ, {"LIVE_REDIS_TEST": "1"}, clear=True):
            self.assertEqual(self.run_checks([check])[0]["status"], "not-run")
            result = self.run_checks([check], True)[0]
            self.assertEqual(result["returncode"], 0)
            self.assertEqual(result["status"], "incomplete")
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.run_checks([check], True)[0]["status"], "not-run")

    def test_configured_database_is_not_used_without_opt_in(self):
        """일반 빌드도 주입된 외부 테스트 DB를 몰래 사용하지 않는다."""
        command = ["./gradlew", ":services:member-service:build"]
        check = {"name": "gradle", "command": command, "external": None}
        with patch.dict(os.environ, {"MEMBER_TEST_DB_URL": "jdbc:postgresql://localhost/test"}, clear=True):
            self.assertTrue(verify.configured_test_database(command, os.environ))
            self.assertEqual(self.run_checks([check])[0]["status"], "not-run")
            self.assertFalse(verify.configured_test_database(
                ["./gradlew", ":services:shopping-service:build"], os.environ))
            self.assertTrue(verify.configured_test_database(["./gradlew", "build"], os.environ))
            self.assertFalse(verify.configured_test_database(
                ["./gradlew", ":services:member-service:spotlessCheck",
                 ":services:shopping-service:build"], os.environ))

    def test_failed_generation_does_not_validate_stale_samples(self):
        """표본 생성에 실패하면 기존 표본 검사로 성공을 만들지 않는다."""
        results = self.run_checks([self.check("gradle", "raise SystemExit(1)"),
                                   self.check("event-samples", "raise SystemExit(99)")])
        self.assertEqual(results[1]["status"], "not-run")
        self.assertNotIn("returncode", results[1])

    def test_format_only_runs_even_with_test_database_configured(self):
        """DB 환경변수가 있어도 테스트하지 않는 포맷 검사는 실행한다."""
        script = self.root / "gradlew"
        script.write_text('#!/bin/sh\nexit 0\n')
        script.chmod(0o755)
        check = {"name": "gradle", "command": ["./gradlew", ":services:member-service:spotlessCheck"],
                 "external": None}
        with patch.dict(os.environ, {"MEMBER_TEST_DB_URL": "jdbc:postgresql://localhost/test"}, clear=True):
            result = self.run_checks([check])[0]
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["returncode"], 0)

    def test_incomplete_generation_does_not_validate_stale_samples(self):
        """종료 코드가 0이어도 테스트 스킵·결과 누락이면 표본 검사를 실행하지 않는다."""
        script = self.root / "gradlew"
        check = {"name": "gradle", "command": ["./gradlew", ":services:live-service:build"],
                 "external": None}
        outputs = ["", "> Task :services:live-service:test SKIPPED",
                   "> Task :services:live-service:test"]
        for output in outputs:
            with self.subTest(output=output), patch.dict(os.environ, {}, clear=True):
                script.write_text("#!/bin/sh\necho '" + output + "'\n")
                script.chmod(0o755)
                results = self.run_checks([check, self.check("event-samples", "raise SystemExit(99)")])
                self.assertEqual(results[0]["returncode"], 0)
                self.assertEqual(results[0]["status"], "incomplete")
                self.assertEqual(results[1]["status"], "not-run")
                self.assertNotIn("returncode", results[1])

    def test_successful_generation_runs_sample_validation(self):
        """테스트 결과가 정상인 빌드 뒤에는 표본 검사를 실행한다."""
        folder = self.root / "services/live-service/build/test-results/test"
        folder.mkdir(parents=True)
        (folder / "TEST-example.xml").write_text('<testsuite tests="1"/>')
        script = self.root / "gradlew"
        script.write_text('#!/bin/sh\necho "> Task :services:live-service:test"\n'
                          'echo "> Task :services:live-service:integrationTest NO-SOURCE"\n')
        script.chmod(0o755)
        check = {"name": "gradle", "command": ["./gradlew", ":services:live-service:build"],
                 "external": None}
        with patch.dict(os.environ, {}, clear=True):
            results = self.run_checks([check, self.check("event-samples", "print('표본 검사 실행')")])
        self.assertEqual([r["status"] for r in results], ["passed", "passed"])
        self.assertEqual(results[1]["returncode"], 0)

    def test_build_without_test_task_output_is_incomplete(self):
        """빌드 성공 문구만 있고 테스트 작업 결과가 없으면 미완료다."""
        script = self.root / "gradlew"
        script.write_text('#!/bin/sh\necho BUILD SUCCESSFUL\n')
        script.chmod(0o755)
        check = {"name": "gradle", "command": ["./gradlew", ":services:member-service:build"],
                 "external": None}
        with patch.dict(os.environ, {}, clear=True):
            result = self.run_checks([check])[0]
        self.assertEqual(result["returncode"], 0)
        self.assertEqual(result["tests"], [])
        self.assertEqual(result["status"], "incomplete")

    def test_build_requires_both_unit_and_integration_task_results(self):
        """한 테스트 작업의 결과만 있어도 빌드 전체를 성공으로 판단하지 않는다."""
        script = self.root / "gradlew"
        script.write_text('#!/bin/sh\necho "> Task :services:member-service:test NO-SOURCE"\n')
        script.chmod(0o755)
        check = {"name": "gradle", "command": ["./gradlew", ":services:member-service:build"],
                 "external": None}
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.run_checks([check])[0]["status"], "incomplete")
        script.write_text(script.read_text() + 'echo "> Task :services:member-service:integrationTest NO-SOURCE"\n')
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.run_checks([check])[0]["status"], "passed")

    def test_gradle_reports_include_skips_cache_and_no_source(self):
        """XML 결과와 캐시 재사용·소스 없음을 함께 표시한다."""
        folder = self.root / "services/member-service/build/test-results/test"
        folder.mkdir(parents=True)
        (folder / "TEST-example.xml").write_text('<testsuite tests="4" failures="0" errors="0" skipped="1"/>')
        output = ('> Task :common-module:testFixturesJar UP-TO-DATE\n'
                  '> Task :services:member-service:test UP-TO-DATE\n'
                  '> Task :services:notification-service:test NO-SOURCE\n')
        results = verify.test_results(self.root, output)
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]["skipped"], 1)
        self.assertEqual(results[0]["state"], "UP-TO-DATE")
        self.assertEqual(results[1]["state"], "NO-SOURCE")
        self.assertEqual(results[1]["reports"], 0)

    def test_skipped_xml_makes_successful_gradle_incomplete(self):
        """Gradle 종료 코드가 0이어도 스킵을 전체 성공으로 처리하지 않는다."""
        folder = self.root / "services/member-service/build/test-results/test"
        folder.mkdir(parents=True)
        (folder / "TEST-example.xml").write_text('<testsuite tests="1" skipped="1"/>')
        script = self.root / "gradlew"
        script.write_text('#!/bin/sh\nprintf "> Task :services:member-service:test\\n"\n')
        script.chmod(0o755)
        result = self.run_checks([{"name": "gradle", "command": ["./gradlew"], "external": None}])[0]
        self.assertEqual(result["status"], "incomplete")


if __name__ == "__main__":
    unittest.main()
