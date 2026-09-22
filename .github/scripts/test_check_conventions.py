import unittest

from check_conventions import validate


def pr(title="feat(commerce): 주문 생성 (#72)", head="feat/#72-order-creation", base="dev"):
    return {"pull_request": {"title": title, "head": {"ref": head}, "base": {"ref": base}}}


class NamingConventionsTest(unittest.TestCase):
    def test_supported_issue_titles(self):
        for title in ("[FEAT][commerce] 주문 생성", "[BUG][common] 잘못된 JSON 오류", "[CI][infra] 테스트 실행"):
            with self.subTest(title=title):
                self.assertEqual([], validate("issues", {"issue": {"title": title}}))

    def test_invalid_issue_type_scope_and_empty_description(self):
        for title in ("[feat][commerce] 주문", "[FIX][common] 오류", "[FEAT][unknown] 주문", "[FEAT][live] ", "[FEAT][live] 제목\n내용"):
            with self.subTest(title=title):
                self.assertTrue(validate("issues", {"issue": {"title": title}}))

    def test_matching_feature_pr_and_stacked_base(self):
        self.assertEqual([], validate("pull_request", pr()))
        self.assertEqual([], validate("pull_request", pr(base="feat/#70-stock")))

    def test_mismatched_issue_is_rejected(self):
        self.assertTrue(validate("pull_request", pr(head="feat/#73-order-creation")))

    def test_legacy_and_invalid_branches_are_rejected(self):
        for head in ("feat/#72", "feat/#72_Order", "feat/#72-Order", "feature/#72-order", "feat/#72-order--creation"):
            with self.subTest(head=head):
                self.assertTrue(validate("pull_request", pr(head=head)))

    def test_invalid_pr_title(self):
        for title in ("feat: 주문 생성 (#72)", "feat(commerce): 주문 생성", "FEAT(commerce): 주문 생성 (#72)", "feat(commerce):  (#72)"):
            with self.subTest(title=title):
                self.assertTrue(validate("pull_request", pr(title=title)))

    def test_integration_exception_is_limited_to_dev_main_pair(self):
        self.assertEqual([], validate("pull_request", pr(head="dev", base="main")))
        self.assertEqual([], validate("pull_request", pr(head="main", base="dev")))
        self.assertTrue(validate("pull_request", pr(head="dev", base="feat/#72-order")))
        self.assertTrue(validate("pull_request", pr(head="main", base="main")))
        self.assertTrue(validate("pull_request", pr(title="merge dev", head="dev", base="main")))

    def test_shell_syntax_in_description_remains_data(self):
        # No execution API is used: arbitrary description text is merely validated.
        self.assertEqual([], validate("pull_request", pr(title="fix(common): $(echo unsafe) `text` (#72)")))


if __name__ == "__main__":
    unittest.main()
