import tempfile
import unittest
from pathlib import Path

from check_test_results import inspect_reports


class TestResultGateTest(unittest.TestCase):
    def test_missing_and_empty_reports_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(inspect_reports([f"{directory}/*.xml"])["passed"])
            report = Path(directory, "TEST-empty.xml")
            report.write_text('<testsuite name="empty" tests="0" skipped="0" failures="0" errors="0"/>')
            self.assertFalse(inspect_reports([str(report)])["passed"])

    def test_failure_skip_missing_required_and_counter_forgery_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory, "TEST-suite.xml")
            for child, counts in [('<failure/>', 'failures="1" skipped="0"'),
                                  ('<skipped/>', 'failures="0" skipped="1"'),
                                  ('', 'failures="0" skipped="0"')]:
                report.write_text(f'<testsuite name="suite" tests="1" errors="0" {counts}>'
                                  f'<testcase name="case">{child}</testcase></testsuite>')
                result = inspect_reports([str(report)])
                self.assertEqual(result["passed"], not child)
                self.assertFalse(inspect_reports([str(report)], ["missing"])["passed"])
            report.write_text(report.read_text().replace('tests="1"', 'tests="100"'))
            self.assertFalse(inspect_reports([str(report)])["passed"])

    def test_only_explicit_suite_can_skip_but_some_tests_must_execute(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory, "TEST-suite.xml")
            report.write_text('<testsuite name="pg" tests="2" skipped="1" failures="0" errors="0">'
                              '<testcase name="a"/><testcase name="b"><skipped/></testcase></testsuite>')
            self.assertTrue(inspect_reports([str(report)], ["pg"], {"pg": 1})["passed"])
            self.assertFalse(inspect_reports([str(report)], allowed_skips={"other": 1})["passed"])
            self.assertFalse(inspect_reports([str(report)], allowed_skips={"pg": 2})["passed"])
            self.assertFalse(inspect_reports([str(report)], allowed_skips={"pg": 1}, minimums={"pg": 2})["passed"])

    def test_display_name_does_not_replace_test_class_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory, "TEST-suite.xml")
            report.write_text('<testsuite name="번역된 설명" tests="1" skipped="0" failures="0" errors="0">'
                              '<testcase name="case" classname="actual.PgTest"/></testsuite>')
            self.assertTrue(inspect_reports([str(report)], ["*.PgTest"], minimums={"*.PgTest": 1})["passed"])


if __name__ == "__main__":
    unittest.main()
