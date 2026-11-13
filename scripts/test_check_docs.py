"""문서와 구현의 연결이 끊기거나 완료 근거가 빠졌을 때 검출하는지 확인한다."""
from pathlib import Path
import subprocess
import tempfile
import unittest

import check_docs


class DocumentChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")
        return file

    def check(self, path="docs/note.md", text=""):
        self.write(path, text)
        return check_docs.check_documents(self.root, {path})

    def test_missing_file_and_heading_are_reported(self):
        """파일 삭제와 제목 변경으로 끊긴 링크를 모두 찾는다."""
        self.write("docs/target.md", "# 실제 제목\n")
        errors = self.check(text="[파일](missing.md) [제목](target.md#이전-제목)")
        self.assertEqual(len(errors), 2)

    def test_korean_and_duplicate_heading_anchors(self):
        """한국어 제목과 반복 제목의 GitHub 앵커를 허용한다."""
        self.write("docs/target.md", "# 검증 결과\n# 검증 결과\n")
        self.assertEqual(self.check(text="[첫 제목](target.md#검증-결과) [둘째](target.md#검증-결과-1)"), [])

    def test_code_examples_and_external_links_are_not_local_targets(self):
        """코드 예시와 외부 주소에는 로컬 파일의 존재를 요구하지 않는다."""
        self.assertEqual(self.check(text='```md\n[예시](missing.md)\n```\n`[예시](missing.md)`\n[외부](https://example.com/x)\n<!-- [주석](missing.md) -->'), [])

    def test_html_local_assets_and_anchor_are_checked(self):
        """HTML의 링크·이미지·앵커를 점검한다."""
        errors = self.check("docs/map.html", '<h1 id="map">지도</h1><a href="#map">이동</a><img src="missing.png">')
        self.assertEqual(len(errors), 1)
        self.assertIn("missing.png", errors[0])

    def test_reference_links_and_undefined_references(self):
        """참조형 링크의 대상 파일과 정의 누락을 확인한다."""
        self.write("docs/target.md", "# 대상")
        self.assertEqual(self.check(text="[대상][source]\n[source]: target.md"), [])
        self.assertIn("정의 없음", self.check(text="[대상][unknown]")[0])
        self.assertIn("링크 없음", self.check(text="[대상][source]\n[source]: missing.md")[0])

    def test_encoded_path_and_repository_root_link(self):
        """공백·한글·번호를 포함한 파일 경로와 루트 기준 경로를 허용한다."""
        self.write("docs/설계 #330.md", "# 설계")
        self.assertEqual(self.check(text="[설계](<설계 %23330.md>) [루트](/docs/설계%20%23330.md)"), [])

    def test_outside_repository_link_is_rejected(self):
        """개인 PC에만 존재하는 저장소 밖 경로를 공유 문서에서 검출한다."""
        self.assertIn("저장소 밖", self.check(text="[개인 경로](../../outside.txt)")[0])

    def test_ignored_link_target_is_rejected(self):
        """현재 PC에만 있는 무시 파일을 CI에서도 유효한 근거로 취급하지 않는다."""
        self.write("docs/local.md", "# 로컬")
        self.write("docs/note.md", "[로컬](local.md)")
        errors = check_docs.check_documents(self.root, {"docs/note.md"}, {"docs/note.md"})
        self.assertIn("추적 대상이 아닌", errors[0])

    def test_requirement_requires_known_status_and_verification(self):
        """상태 오타와 검증 경로가 없는 요구사항을 검출한다."""
        errors = self.check("docs/requirements/sample.md", "# 요구사항\n상태: 끝남\n")
        self.assertEqual(len(errors), 2)

    def test_draft_requirement_can_link_verification_plan(self):
        """초안에는 구현 테스트 대신 검증 계획을 연결할 수 있다."""
        self.write("docs/plan.md", "# 계획")
        self.assertEqual(self.check("docs/requirements/sample.md", "# 요구사항\n상태: 초안\n검증: [계획](../plan.md)"), [])

    def test_completed_requirement_needs_method_acceptance_and_execution_record(self):
        """완료한 요구사항은 검증 방식·수용 기준·실행 기록이 필요하다."""
        self.write("docs/plan.md", "# 계획")
        errors = self.check("docs/requirements/sample.md", "# 요구사항\n상태: 완료\n검증: [계획](../plan.md)")
        self.assertEqual(len(errors), 3)

    def test_completed_requirement_rejects_unfinished_acceptance(self):
        """검증 링크가 있어도 미완료 수용 기준이 남으면 완료로 인정하지 않는다."""
        self.write("scripts/test_sample.py", "")
        self.write("docs/worklog/2026-11-13.md", "# 실제 실행 결과")
        text = "# 요구사항\n상태: 완료\n검증 방식: 자동\n검증: [테스트](../../scripts/test_sample.py)\n근거: [기록](../worklog/2026-11-13.md)\n- [x] 확인한 조건\n"
        self.assertEqual(self.check("docs/requirements/sample.md", text), [])
        self.assertIn("미완료 수용 기준", self.check("docs/requirements/sample.md", text + "- [ ] 남은 검증")[0])

    def test_manual_verification_can_complete_document_requirements(self):
        """브라우저·설계 검토도 파일 이름 대신 검증 방법과 실행 기록으로 연결한다."""
        self.write('docs/design.md', '# 화면 검토 방법')
        self.write('docs/worklog/2026-11-13.md', '# 확인 결과')
        text = '# 요구사항\n상태: 완료\n검증 방식: 수동\n검증: [방법](../design.md)\n근거: [기록](../worklog/2026-11-13.md)\n- [x] 화면 확인\n'
        self.assertEqual(self.check('docs/requirements/sample.md', text), [])

    def test_heading_code_text_is_part_of_anchor(self):
        """인라인 코드가 있는 제목의 앵커에서도 코드 내용을 보존한다."""
        self.write('docs/target.md', '# `API` 검사\n')
        self.assertEqual(self.check(text='[제목](target.md#api-검사)'), [])

    def test_directory_is_not_verification_or_daily_evidence(self):
        """디렉터리 존재만으로 검증 방법이나 일일 실행 기록을 대신하지 못한다."""
        (self.root/'docs/worklog').mkdir(parents=True)
        text = '# 요구사항\n상태: 완료\n검증 방식: 수동\n검증: [폴더](../)\n근거: [폴더](../worklog/)\n- [x] 완료\n'
        self.assertEqual(len(self.check('docs/requirements/sample.md', text)), 2)

    def test_git_inventory_includes_new_documents_and_excludes_ignored_files(self):
        """새 문서를 커밋하기 전에도 검사하고 무시한 문서는 검사하지 않는다."""
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.write(".gitignore", "ignored/\n")
        self.write("docs/new.md", "[끊긴 링크](missing.md)")
        self.write("ignored/bad.md", "[끊긴 링크](missing.md)")
        self.assertEqual(check_docs.main(["--root", str(self.root)]), 1)
        self.write("docs/new.md", "# 새 문서")
        self.assertEqual(check_docs.main(["--root", str(self.root)]), 0)
