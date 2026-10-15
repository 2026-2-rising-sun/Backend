from pathlib import Path
import subprocess
import tempfile
import unittest

from classify_changes import Change, classify, comments_only, diff_changes, event_plan

JAVA = "services/commerce-service/src/main/java/example/Example.java"
CONTROLLER = b'@RestController class Example { @GetMapping("/orders") void get() {} }'


class ClassificationTests(unittest.TestCase):
    def test_documents_do_not_build_or_test(self):
        plan = classify([Change(path, b"old", b"new") for path in
                         ("README.md", "AGENTS.md", "docs/notes.txt", "docs/ci.md")])
        self.assertEqual(plan["gradle_tasks"], [])
        self.assertFalse(plan["api"])
        self.assertFalse(plan["run_tests"])

    def test_runtime_resources_are_not_skipped_as_documentation(self):
        for path in ("services/member-service/src/main/resources/README.md",
                     "README.sh", "unknown-script.py"):
            with self.subTest(path=path):
                self.assertTrue(classify([Change(path, b"old", b"new")])["run_tests"])

    def test_comments_and_whitespace_only_run_module_format(self):
        before = b'class Example { int a = 1; }'
        for after in (b'// comment\nclass Example { int a = 1; }',
                      b'/** doc */ class Example { int /* why */ a = 1; }',
                      b'class Example {\n int a = 1;\n} '):
            with self.subTest(after=after):
                plan = classify([Change(JAVA, before, after)])
                self.assertEqual(plan["gradle_tasks"], [":services:commerce-service:spotlessCheck"])
                self.assertFalse(plan["run_tests"])
                self.assertFalse(plan["api"])

    def test_literals_operators_annotations_and_invalid_java_build(self):
        pairs = [
            (b'class Example {String a="//old";}', b'class Example {String a="//new";}'),
            (b'class Example {String a="a b";}', b'class Example {String a="ab";}'),
            (b'class Example {char a=\'a\';}', b'class Example {char a=\'b\';}'),
            (b'class Example {int a=1+2;}', b'class Example {int a=1-2;}'),
            (b'@Deprecated class Example {}', b'class Example {}'),
            (b'class Example {}', b'class Example {'),
            (b'class Example {}', rb'//\u000a class Changed {}'),
            (b'class Example {String a="""\nhello world\n""";}',
             b'class Example {String a="""\nhello  world\n""";}'),
        ]
        for before, after in pairs:
            with self.subTest(after=after):
                plan = classify([Change(JAVA, before, after)])
                self.assertEqual(plan["gradle_tasks"], [":services:commerce-service:build"])

    def test_added_deleted_and_mode_changed_java_are_not_comment_only(self):
        for change in (Change(JAVA, None, b"//new"), Change(JAVA, b"//old", None),
                       Change(JAVA, b'class Example {}', b'class Example {}', False)):
            self.assertFalse(comments_only(change))
            self.assertTrue(classify([change])["run_tests"])

    def test_multiple_services_and_comment_only_other_module(self):
        plan = classify([
            Change(JAVA, b'class Example {}', b'class Example {int a;}'),
            Change("services/member-service/src/main/resources/application.yml", b"a", b"b"),
            Change("services/live-service/src/test/java/Example.java", b'class Example {}',
                   b'// comment\nclass Example {}'),
        ])
        self.assertEqual(plan["gradle_tasks"], [":services:commerce-service:build",
                         ":services:member-service:build", ":services:live-service:spotlessCheck"])

    def test_same_module_format_is_covered_by_build(self):
        plan = classify([Change(JAVA, b'class Example {}', b'// comment\nclass Example {}'),
                         Change("services/commerce-service/build.gradle.kts", b"a", b"b")])
        self.assertEqual(plan["gradle_tasks"], [":services:commerce-service:build"])

    def test_common_comments_only_format_but_code_changes_build_everything(self):
        path = "common-module/src/main/java/Example.java"
        plan = classify([Change(path, b'class Example {}', b'/** doc */ class Example {}')])
        self.assertEqual(plan["gradle_tasks"], [":common-module:spotlessCheck"])
        self.assertFalse(plan["api"])
        plan = classify([Change(path, b'class Example {}', b'class Example {int a;}')])
        self.assertEqual(plan["gradle_tasks"], ["build"])

    def test_shared_build_and_unclassified_changes_use_full_fallback(self):
        for path in ("settings.gradle.kts", "gradle/wrapper/gradle-wrapper.properties",
                     "build-logic/new.gradle.kts", "services/new-service/build.gradle.kts"):
            with self.subTest(path=path):
                self.assertEqual(classify([Change(path, b"a", b"b")])["gradle_tasks"], ["build"])

    def test_controller_body_only_does_not_check_api(self):
        after = CONTROLLER.replace(b'void get() {}', b'void get() { int a = 1; }')
        self.assertFalse(classify([Change(JAVA, CONTROLLER, after)])["api"])

    def test_controller_mapping_add_delete_and_change_check_api(self):
        for before, after in ((CONTROLLER, None), (None, CONTROLLER),
                              (CONTROLLER, CONTROLLER.replace(b'/orders', b'/carts')),
                              (CONTROLLER, CONTROLLER.replace(b'GetMapping', b'PostMapping'))):
            with self.subTest(after=after):
                self.assertTrue(classify([Change(JAVA, before, after)])["api"])

    def test_fake_comment_mapping_does_not_trigger_api(self):
        before = b'class Example {}'
        after = b'// @RestController @GetMapping("/fake")\nclass Example {}'
        self.assertFalse(classify([Change(JAVA, before, after)])["api"])

    def test_unparseable_mapping_conservatively_checks_api(self):
        after = CONTROLLER.replace(b'"/orders"', b'UNKNOWN_PATH')
        self.assertTrue(classify([Change(JAVA, CONTROLLER, after)])["api"])

    def test_spec_only_checks_api_without_java(self):
        plan = classify([Change("contracts/api/common.yaml", b"a", b"b")])
        self.assertTrue(plan["api"])
        self.assertEqual(plan["gradle_tasks"], [])
        self.assertFalse(plan["run_tests"])

    def test_tools_changes_run_only_relevant_checks(self):
        for path, expected in ((".github/workflows/ci.yml", "workflow"),
                               (".github/scripts/ci/classify_changes.py", "classifier"),
                               (".github/scripts/api/requirements.txt", "api_tests"),
                               (".github/scripts/example-tool.py", "classifier"),
                               ("scripts/local/auth-env.cjs", "local_auth")):
            with self.subTest(path=path):
                plan = classify([Change(path, b"a", b"b")])
                self.assertTrue(plan[expected])
                self.assertEqual(plan["gradle_tasks"], [])

    def test_manual_ci_is_full_and_unknown_event_cannot_skip(self):
        self.assertEqual(event_plan(Path("."), "workflow_dispatch", {})["gradle_tasks"], ["build"])
        self.assertTrue(event_plan(Path("."), "unknown", {})["run_tests"])

    def test_migration_add_modify_delete_only_selects_changed_service(self):
        path = "services/commerce-service/src/main/resources/db/migration/V99__example.sql"
        for before, after in ((None, b"CREATE TABLE example(id bigint)"),
                              (b"old", b"new"), (b"old", None)):
            with self.subTest(before=before, after=after):
                plan = classify([Change(path, before, after)])
                self.assertEqual(plan["migration_tasks"], [":services:commerce-service:migrationTest"])
                self.assertEqual(plan["redis_tasks"], [])

    def test_unrelated_service_code_does_not_start_external_services(self):
        plan = classify([Change(JAVA, b'class Example {}', b'class Example {int a;}')])
        self.assertEqual(plan["migration_tasks"], [])
        self.assertEqual(plan["redis_tasks"], [])

    def test_redis_code_and_callers_select_redis_tag_only(self):
        for path in ("services/live-service/src/main/java/com/shoppinglive/live/stream/StreamRelay.java",
                     "services/live-service/src/main/java/com/shoppinglive/live/broadcast/service/ChatWriteService.java"):
            plan = classify([Change(path, b'class Example {}', b'class Example {int a;}')])
            self.assertEqual(plan["redis_tasks"], [":services:live-service:redisTest"])
            self.assertEqual(plan["migration_tasks"], [])

    def test_redis_comment_change_does_not_start_redis(self):
        source = b'class Example { StringRedisTemplate redis; }'
        path = "services/live-service/src/main/java/com/shoppinglive/live/stream/StreamRelay.java"
        plan = classify([Change(path, source, b'// explanation\n'+source)])
        self.assertEqual(plan["redis_tasks"], [])
        self.assertFalse(plan["run_tests"])

    def test_event_checks_follow_producer_builds_and_shared_changes(self):
        for service in ("commerce-service", "live-service"):
            plan = classify([Change("services/" + service + "/src/main/java/Example.java",
                                    b'class Example {}', b'class Example { int value; }')])
            self.assertTrue(plan["events"])
            self.assertTrue(plan["event_samples"])
        plan = classify([Change("services/member-service/src/main/java/Example.java",
                                b'class Example {}', b'class Example { int value; }')])
        self.assertFalse(plan["event_samples"])
        self.assertFalse(plan["events"])
        self.assertTrue(classify([Change("contracts/events/commerce-coupon.yaml", b"a", b"b")])["event_samples"])
        self.assertFalse(classify([Change("README.md", b"a", b"b")])["events"])

    def test_notification_migration_is_selected_by_sql_and_full_fallback(self):
        plan = classify([Change("services/notification-service/src/main/resources/db/migration/V1__notification_baseline.sql", b"a", b"b")])
        self.assertEqual(plan["migration_tasks"], [":services:notification-service:migrationTest"])
        self.assertIn(":services:notification-service:migrationTest", classify([], "fallback")["migration_tasks"])

    def test_redis_config_values_not_unrelated_yaml_keys_select_redis(self):
        path = "services/live-service/src/main/resources/application.yml"
        before = b'spring:\n  data:\n    redis:\n      timeout: 1s\nserver:\n  port: 8084\n'
        unrelated = before.replace(b'8084', b'8085')
        related = before.replace(b'1s', b'2s')
        self.assertEqual(classify([Change(path, before, unrelated)])["redis_tasks"], [])
        self.assertEqual(classify([Change(path, before, b'# comment\n'+before)])["redis_tasks"], [])
        for after in (related, None, b'spring: [invalid'):
            self.assertEqual(classify([Change(path, before, after)])["redis_tasks"], [":services:live-service:redisTest"])

    def test_redis_usage_outside_registered_paths_and_removed_usage_is_detected(self):
        path = "services/member-service/src/main/java/example/Cache.java"
        before = b'class Cache {}'
        for after in (b'class Cache {RedisCacheManager manager;}',
                      b'class Cache {StringRedisTemplate redis;}'):
            for old, new in ((before, after), (after, before)):
                self.assertEqual(classify([Change(path, old, new)])["redis_tasks"], [":services:member-service:redisTest"])
        # Spring caching may use a non-Redis backend.
        self.assertEqual(classify([Change(path, before, b'@Cacheable("x") class Cache {}')])["redis_tasks"], [])

    def test_non_redis_cache_configuration_does_not_select_redis(self):
        path = "services/member-service/src/main/resources/application.yml"
        for backend in ("caffeine", "simple", "none"):
            after = f'spring:\n  cache:\n    type: {backend}\n    cache-names: members\n'.encode()
            self.assertEqual(classify([Change(path, b'spring: {}', after)])["redis_tasks"], [])
        self.assertEqual(classify([Change(path, b'spring: {}', b'spring:\n  cache:\n    cache-names: members')])["redis_tasks"], [])
        for config in (b'spring:\n  cache:\n    type: redis\n',
                       b'spring:\n  cache:\n    redis:\n      time-to-live: 10s\n'):
            self.assertEqual(classify([Change(path, b'spring: {}', config)])["redis_tasks"], [":services:member-service:redisTest"])

    def test_tagged_test_changes_select_their_external_suite(self):
        for tag, key, task in (("migration", "migration_tasks", "migrationTest"),
                               ("redis", "redis_tasks", "redisTest")):
            path = "services/live-service/src/integrationTest/java/example/Test.java"
            for annotation in (f'@Tag("{tag}")', f'@Tag(value="{tag}")'):
                source = (annotation+' class Test {}').encode()
                plan = classify([Change(path, source, source.replace(b'{}', b'{int x;}'))])
                self.assertEqual(plan[key], [":services:live-service:"+task])
                self.assertEqual(classify([Change(path, source, b'// note\n'+source)])[key], [])


class GitDiffTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args]).decode().strip()

    def commit(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        self.git("add", "--all")
        self.git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
                 "commit", "-qm", "test fixture")
        return self.git("rev-parse", "HEAD")

    def test_move_between_services_checks_old_and_new_service(self):
        old_path = "services/member-service/src/main/java/Example.java"
        base = self.commit(old_path, "class Example {}")
        (self.root / old_path).unlink()
        head = self.commit(JAVA, "class Example {}")
        plan = classify(diff_changes(self.root, base, head))
        self.assertEqual(plan["gradle_tasks"], [":services:commerce-service:build",
                                              ":services:member-service:build"])

    def test_push_uses_before_after_and_new_branch_falls_back(self):
        base = self.commit("README.md", "old")
        head = self.commit("README.md", "new")
        self.assertFalse(event_plan(self.root, "push", {"before": base, "after": head})["run_tests"])
        for before, after in (("0" * 40, head), ("a" * 40, head), (base, base)):
            self.assertTrue(event_plan(self.root, "push", {"before": before, "after": after})["run_tests"])

    def test_pr_compares_tested_merge_checkout_with_base(self):
        base = self.commit("README.md", "old")
        self.commit(JAVA, "class Example {}")
        plan = event_plan(self.root, "pull_request", {"pull_request": {"base": {"sha": base}}})
        self.assertEqual(plan["gradle_tasks"], [":services:commerce-service:build"])

    def test_file_permission_change_is_not_skipped(self):
        base = self.commit(JAVA, "class Example {}")
        (self.root / JAVA).chmod(0o755)
        self.git("add", "--all")
        self.git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
                 "commit", "-qm", "mode fixture")
        plan = classify(diff_changes(self.root, base, self.git("rev-parse", "HEAD")))
        self.assertTrue(plan["run_tests"])

    def test_missing_pr_base_cannot_skip_tests(self):
        self.commit("README.md", "doc")
        plan = event_plan(self.root, "pull_request", {"pull_request": {"base": {"sha": "a" * 40}}})
        self.assertEqual(plan["gradle_tasks"], ["build"])


class HarnessSelectionTests(unittest.TestCase):
    def test_runner_changes_select_harness_tests_without_build(self):
        """실행기 변경은 자체 검사만 선택하고 업무 빌드를 요구하지 않는다."""
        for path in ("harness/verify.py", "harness/test_verify.py"):
            with self.subTest(path=path):
                plan = classify([Change(path, None, b"code")])
                self.assertTrue(plan["harness"])
                self.assertEqual(plan["gradle_tasks"], [])
        self.assertTrue(classify([], "비교 불가")["harness"])

    def test_harness_documents_do_not_select_runner_tests(self):
        """같은 폴더의 지침 변경은 문서로 분류하고 실행기 검사를 추가하지 않는다."""
        plan = classify([Change("harness/README.md", b"old", b"new")])
        self.assertFalse(plan["harness"])
        self.assertEqual(plan["gradle_tasks"], [])

    def test_ci_runs_harness_tests_only_when_selected(self):
        """로컬 실행기 회귀 검사를 CI 선택 결과와 연결한다."""
        import yaml
        root = Path(__file__).resolve().parents[3]
        ci = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        step = next(s for s in ci["jobs"]["build"]["steps"]
                    if s.get("name") == "Check local verification runner")
        self.assertIn("outputs.harness == 'true'", step["if"])
        self.assertIn("unittest discover -s harness", step["run"])


class WorkflowTests(unittest.TestCase):
    def test_workflow_can_complete_without_gradle_and_publish_requires_build(self):
        import yaml
        root = Path(__file__).resolve().parents[3]
        ci = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        self.assertIn("needs.build.outputs.application-build == 'true'", ci["jobs"]["publish"]["if"])
        steps = ci["jobs"]["build"]["steps"]
        java = next(step for step in steps if step.get("uses", "").startswith("actions/setup-java@"))
        self.assertIn("gradle_tasks", java["if"])
        reports = next(step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@")
                       and step["with"]["name"].startswith("test-reports"))
        self.assertIn("run_tests == 'true'", reports["if"])

    def test_external_containers_start_only_for_selected_tag_tasks(self):
        import yaml
        root = Path(__file__).resolve().parents[3]
        self.assertFalse((root / ".github/workflows/postgres.yml").exists())
        for workflow in (root / ".github/workflows").glob("*.yml"):
            definition = yaml.safe_load(workflow.read_text())
            for job in definition["jobs"].values():
                self.assertFalse(job.get("services"), workflow.name)
        ci = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        steps = ci["jobs"]["build"]["steps"]
        for step in steps:
            if "docker run" in step.get("run", ""):
                self.assertTrue("migration_tasks != '[]'" in step["if"] or "redis_tasks != '[]'" in step["if"])
        ordinary = next(i for i, s in enumerate(steps) if s.get("name") == "Run selected Gradle work")
        postgres = next(i for i, s in enumerate(steps) if s.get("name") == "Start PostgreSQL only for migration checks")
        self.assertLess(ordinary, postgres)


if __name__ == "__main__":
    unittest.main()
