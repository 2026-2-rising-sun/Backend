"""OpenAPI YAML과 로컬 참조를 오프라인 HTML로 표시한다. 스키마 의미 검증기는 아니다."""
import argparse
from hashlib import sha256
from html import escape
import os
from pathlib import Path
from urllib.parse import unquote, urlsplit

import yaml
from doc_html import page

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('commercecore', 'payment', 'catalog', 'member', 'live', 'notification')
METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'}
RENDERER_VERSION = '1'


def anchor(file, pointer):
    return 'ref-' + sha256((file.name + '#' + pointer).encode()).hexdigest()[:20]


def reference(source, value, root):
    parsed = urlsplit(value)
    if parsed.scheme or parsed.netloc:
        raise ValueError(f'원격 참조는 지원하지 않습니다: {value}')
    target = (source.parent / unquote(parsed.path)).resolve() if parsed.path else source
    if not target.is_relative_to((root / 'contracts/api').resolve()):
        raise ValueError(f'계약 디렉터리 밖 참조: {value}')
    return target, unquote(parsed.fragment)


def documents(source, root):
    found, pending = {}, [source]
    while pending:
        file = pending.pop()
        if file in found:
            continue
        document = yaml.safe_load(file.read_text())
        if not isinstance(document, dict):
            raise ValueError(f'객체 형태의 계약이 필요합니다: {file}')
        found[file] = document
        nodes = [document]
        while nodes:
            node = nodes.pop()
            if isinstance(node, dict):
                if '$ref' in node:
                    pending.append(reference(file, node['$ref'], root)[0])
                nodes.extend(node.values())
            elif isinstance(node, list):
                nodes.extend(node)
    # 순환 $ref는 펼치지 않고 해당 원본 앵커로 이동한다.
    for file, document in found.items():
        pending = [document]
        while pending:
            node = pending.pop()
            if isinstance(node, dict):
                if '$ref' in node:
                    target, pointer = reference(file, node['$ref'], root)
                    value = found[target]
                    if pointer and not pointer.startswith('/'):
                        raise ValueError(f'JSON Pointer가 아닌 참조: {pointer}')
                    for part in pointer.split('/')[1:]:
                        part = part.replace('~1', '/').replace('~0', '~')
                        value = value[int(part)] if isinstance(value, list) else value[part]
                pending.extend(node.values())
            elif isinstance(node, list):
                pending.extend(node)
    return found


def render_value(value, file, root, pointer='', anchors=False):
    ident = f' id="{anchor(file, pointer)}"' if anchors else ''
    if isinstance(value, dict):
        parts = []
        for key, child in value.items():
            child_pointer = pointer + '/' + str(key).replace('~', '~0').replace('/', '~1')
            if key == '$ref':
                target, fragment = reference(file, child, root)
                body = f'<a href="#{anchor(target, fragment)}">{escape(child)}</a>'
                if anchors:
                    body = f'<span id="{anchor(file, child_pointer)}">{body}</span>'
            else:
                body = render_value(child, file, root, child_pointer, anchors)
            parts.append(f'<dt>{escape(str(key))}</dt><dd>{body}</dd>')
        return f'<dl{ident}>' + ''.join(parts) + '</dl>'
    if isinstance(value, list):
        return f'<ol{ident}>' + ''.join(
            '<li>' + render_value(v, file, root, pointer + '/' + str(i), anchors) + '</li>'
            for i, v in enumerate(value)) + '</ol>'
    text = 'null' if value is None else str(value).lower() if isinstance(value, bool) else str(value)
    return f'<span{ident}>{escape(text)}</span>'


def render(root, service):
    source = (root / f'contracts/api/{service}-service.yaml').resolve()
    output = root / f'docs/design-docs/services/{service}/api.html'
    bundle = documents(source, root)
    spec = bundle[source]
    fingerprint = sha256(b''.join(file.name.encode() + file.read_bytes()
                                  for file in sorted(bundle))).hexdigest()
    title = spec['info']['title']
    nav = '<a href="README.md">모듈 안내</a> <a href="' + escape(os.path.relpath(source, output.parent)) + '">계약 YAML</a>'
    body = (f'<p>{escape(spec["info"].get("description", ""))}</p>'
            f'<p class="muted">OpenAPI {escape(str(spec["openapi"]))} · 계약 {escape(str(spec["info"]["version"]))} · 렌더러 {RENDERER_VERSION}</p>'
            '<p>계약에 선언된 내용입니다. 실제 응답·인증·운영 서버의 동작 검증은 별도입니다.</p>'
            '<label for="search">API 찾기</label> <input id="search" type="search" placeholder="경로·메서드·설명 검색">'
            '<p id="count" role="status"></p><div class="controls"><button id="expand">모두 펼치기</button><button id="collapse">모두 접기</button></div>')
    operations = []
    for route, path in spec.get('paths', {}).items():
        for method, operation in path.items():
            if method not in METHODS:
                continue
            label = f'{method.upper()} {route}'
            details = dict(operation)
            # 경로 수준 파라미터와 최상위 인증·서버를 함께 표시한다.
            inherited = {k: path[k] for k in ('parameters', 'servers') if k in path}
            if inherited:
                details['경로 공통 선언'] = inherited
            details['적용 인증'] = operation.get('security', spec.get('security', []))
            details['적용 서버'] = operation.get('servers', path.get('servers', spec.get('servers', [])))
            operations.append('<details class="operation card"><summary><span class="badge">'
                              + escape(method.upper()) + '</span> <code>' + escape(route) + '</code> — '
                              + escape(operation.get('summary', '')) + '</summary>'
                              + render_value(details, source, root) + '</details>')
    body += ''.join(operations)
    body += '<h2>전체 원본·스키마 참조</h2><p>링크를 누르면 아래의 원본 선언으로 이동합니다. 조건·예시·확장 필드도 원본 그대로 표시합니다.</p>'
    for file, document in sorted(bundle.items()):
        body += '<details class="source card"><summary>' + escape(file.name) + '</summary>'
        body += render_value(document, file, root, anchors=True) + '</details>'
    body += f'<p class="muted">입력 SHA-256: <code>{fingerprint}</code></p>'
    script = '''
const operations=[...document.querySelectorAll('.operation')];
function filter(){const q=document.querySelector('#search').value.toLowerCase();operations.forEach(n=>n.hidden=!n.textContent.toLowerCase().includes(q));document.querySelector('#count').textContent=`${operations.filter(n=>!n.hidden).length} / ${operations.length}개 API`;}
document.querySelector('#search').addEventListener('input',filter);filter();
document.querySelector('#expand').onclick=()=>operations.filter(n=>!n.hidden).forEach(n=>n.open=true);
document.querySelector('#collapse').onclick=()=>operations.forEach(n=>n.open=false);
function reveal(){const id=decodeURIComponent(location.hash.slice(1));if(!id)return;const target=document.getElementById(id);if(!target)return;let n=target;while(n){if(n.tagName==='DETAILS')n.open=true;n=n.parentElement;}target.scrollIntoView();}
window.addEventListener('hashchange',reveal);reveal();
'''
    return output, page(title, nav, body, script)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='생성 결과를 쓰지 않고 최신성 확인')
    args = parser.parse_args(argv)
    stale = []
    for service in SERVICES:
        output, html = render(ROOT, service)
        if args.check:
            if not output.exists() or output.read_text() != html:
                stale.append(output.relative_to(ROOT).as_posix())
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(html)
    for path in stale:
        print('재생성 필요:', path)
    print(f'API HTML {len(SERVICES)}개, 불일치 {len(stale)}개')
    return int(bool(stale))


if __name__ == '__main__':
    raise SystemExit(main())
