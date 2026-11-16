"""생성 문서에서 참조·안전한 표시·재생성 누락을 확인한다."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_docs
import render_api_docs as api
import render_design_docs as design


class RenderChecks(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.source = self.root / 'contracts/api/example-service.yaml'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('''openapi: 3.1.0
info: {title: 예제, version: '1'}
security: [{ServiceToken: []}]
paths:
  /example:
    parameters: [{name: id, in: query}]
    get:
      summary: '<script>alert(1)</script>'
      responses:
        '200':
          description: 성공
          content:
            application/json:
              schema: {$ref: 'common.yaml#/components/schemas/Example'}
components:
  securitySchemes:
    ServiceToken: {type: apiKey, in: header, name: X-Service-Token}
''')
        (self.source.parent / 'common.yaml').write_text('''components:
  schemas:
    Example:
      type: object
      properties:
        next: {$ref: '#/components/schemas/Example'}
''')

    def test_local_and_recursive_schema_references_have_real_anchors(self):
        """공통·자기 참조를 무한 확장하지 않고 존재하는 원본 앵커로 연결한다."""
        output, html = api.render(self.root, 'example')
        output.parent.mkdir(parents=True)
        output.write_text(html)
        (output.parent / 'api.md').write_text(api.markdown_for(self.root, 'example')[1])
        self.assertEqual(check_docs.check_documents(self.root, [output.relative_to(self.root).as_posix()]), [])
        self.assertIn('X-Service-Token', html)
        self.assertIn('경로 공통 선언', html)
        self.assertNotIn('<script>alert(1)</script>', html)
        self.assertIn('&lt;script&gt;alert(1)&lt;/script&gt;', html)

    def test_missing_pointer_and_remote_reference_fail(self):
        """해결할 수 없는 참조를 조용히 생략하지 않는다."""
        original = self.source.read_text()
        self.source.write_text(original.replace('/schemas/Example', '/schemas/Missing'))
        with self.assertRaises(KeyError):
            api.render(self.root, 'example')
        self.source.write_text(original.replace('common.yaml#', 'https://example.com/common.yaml#'))
        with self.assertRaises(ValueError):
            api.render(self.root, 'example')

    def test_contract_and_shared_schema_changes_make_output_stale(self):
        """원본과 공통 스키마 변경으로 오래된 HTML이 남으면 검사에 실패한다."""
        with patch.object(api, 'ROOT', self.root), patch.object(api, 'SERVICES', ('example',)):
            self.assertEqual(api.main([]), 0)
            self.assertEqual(api.main(['--check']), 0)
            shared = self.source.parent / 'common.yaml'
            shared.write_text(shared.read_text().replace('type: object', 'type: object\n      description: 변경'))
            self.assertEqual(api.main(['--check']), 1)

    def test_module_api_views_cover_each_route_once_and_reject_unknown_ownership(self):
        """모듈별 명세가 경로를 중복·누락하지 않고 새 미분류 API를 알린다."""
        spec = api.yaml.safe_load(self.source.read_text())
        paths = ['/v1/seller/products', '/v1/sales', '/v1/cart/items', '/v1/orders', '/v1/coupons']
        spec['paths'] = {path: {'get': {'responses': {'200': {'description': '성공'}}}} for path in paths}
        self.source.write_text(api.yaml.safe_dump(spec, allow_unicode=True))
        covered = []
        for module in ('product', 'sku', 'cart', 'order', 'coupon'):
            _, md, _, _, selected = api.markdown_for(self.root, 'example', module)
            self.assertEqual(len(selected), 1)
            covered.extend(row[0] for row in selected)
            self.assertIn('## GET ' + selected[0][0], md)
        self.assertCountEqual(covered, paths)
        with self.assertRaises(ValueError):
            api.module_for('/v1/unclassified')

    def test_shared_authentication_reference_is_in_module_reference_closure(self):
        """서비스 인증의 공통 참조도 스키마 참조와 함께 보존한다."""
        spec = api.yaml.safe_load(self.source.read_text())
        spec['components']['securitySchemes'] = {'BearerAuth': {'$ref': 'common.yaml#/components/securitySchemes/BearerAuth'}}
        self.source.write_text(api.yaml.safe_dump(spec, allow_unicode=True))
        common = self.source.parent / 'common.yaml'
        shared = api.yaml.safe_load(common.read_text())
        shared['components']['securitySchemes'] = {'BearerAuth': {'type': 'http', 'scheme': 'bearer'}}
        common.write_text(api.yaml.safe_dump(shared))
        output, html = api.render(self.root, 'example')
        output.parent.mkdir(parents=True)
        output.write_text(html)
        output.with_suffix('.md').write_text(api.markdown_for(self.root, 'example')[1])
        self.assertEqual(check_docs.check_documents(self.root, [output.relative_to(self.root).as_posix()]), [])

    def test_authored_html_is_preserved_and_markdown_change_requires_review(self):
        """수작업 그림은 덮어쓰지 않고 짝 원본 변경을 미완료로 알린다."""
        from hashlib import sha256
        source = self.root / 'docs/design-docs/services/payment/design.md'
        source.parent.mkdir(parents=True)
        source.write_text('# 목표 설계\n\n<!-- html: authored -->\n기존 그림 설명\n')
        html = '<p>보존한 그림</p><!-- md-sha256: ' + sha256(source.read_bytes()).hexdigest() + ' -->'
        source.with_suffix('.html').write_text(html)
        with patch.object(design, 'ROOT', self.root):
            self.assertEqual(design.main(['--check']), 0)
            source.write_text(source.read_text() + '\n조건 변경\n')
            self.assertEqual(design.main(['--check']), 1)
            self.assertEqual(source.with_suffix('.html').read_text(), html)

    def test_design_generation_tracks_conditions_and_rejects_bad_table(self):
        """전이 조건과 MD 수정이 시각 자료에 반영되며 열이 빠진 표를 거절한다."""
        source = self.root / 'docs/design-docs/states.md'
        source.parent.mkdir(parents=True)
        source.write_text('# 상태\n\n<!-- visual: relations -->\n\n## 전이\n\n| 출발 | 도착 | 조건 |\n|---|---|---|\n| PROCESSING | UNKNOWN | 응답 유실 |\n')
        with patch.object(design, 'ROOT', self.root):
            self.assertEqual(design.main([]), 0)
            html = source.with_suffix('.html').read_text()
            self.assertIn('응답 유실', html)
            self.assertIn('data-source="PROCESSING"', html)
            self.assertNotIn('visual: relations', html)
            self.assertEqual(design.main(['--check']), 0)
            source.write_text(source.read_text().replace('응답 유실', '조회 미확정'))
            self.assertEqual(design.main(['--check']), 1)
        source.write_text(source.read_text().replace('| PROCESSING | UNKNOWN | 조회 미확정 |', '| PROCESSING | UNKNOWN |'))
        with self.assertRaises(ValueError):
            design.render(source)
