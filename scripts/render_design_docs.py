"""설계 MD의 표를 관계·전이 탐색 HTML로 변환한다. MD가 설명과 시각 자료의 원본이다."""
import argparse
from hashlib import sha256
from html import escape
from pathlib import Path
import re
import textwrap

from doc_html import page

ROOT = Path(__file__).resolve().parents[1]


def inline(text):
    # 저장소에서 작성하는 기본 링크·인라인 코드만 표시한다. HTML 원문은 실행하지 않는다.
    parts, offset = [], 0
    pattern = r'`([^`]+)`|\[([^\]]+)\]\(([^\s)]+)\)'
    for match in re.finditer(pattern, text):
        parts.append(escape(text[offset:match.start()]))
        if match[1] is not None:
            parts.append('<code>' + escape(match[1]) + '</code>')
        else:
            url = match[3]
            if re.match(r'(?i)(javascript|data):', url):
                raise ValueError('문서 링크에서 실행 스킴을 사용할 수 없습니다.')
            parts.append('<a href="' + escape(url, quote=True) + '">' + escape(match[2]) + '</a>')
        offset = match.end()
    parts.append(escape(text[offset:]))
    return ''.join(parts)


def cells(line):
    return [cell.strip() for cell in line.strip().strip('|').split('|')]


def relation(source, target, condition, index):
    def label(text, x):
        lines = textwrap.wrap(text, 30) or ['']
        return ''.join(f'<text x="{x}" y="{45+i*17}" text-anchor="middle">{escape(t)}</text>'
                       for i, t in enumerate(lines))
    # 전이를 개별 행으로 그려 순환·양방향 관계에서도 선이 겹치지 않게 한다.
    return (f'<article class="edge-row card" data-source="{escape(source, quote=True)}" data-target="{escape(target, quote=True)}">'
            f'<div class="diagram"><svg viewBox="0 0 760 110" role="img" aria-label="{escape(source + " → " + target, quote=True)}">'
            f'<rect x="10" y="15" width="300" height="80" rx="10"/>{label(source,160)}'
            f'<path d="M315 55 H435 M425 47 L435 55 L425 63"/>'
            f'<rect x="440" y="15" width="300" height="80" rx="10"/>{label(target,590)}'
            f'</svg></div><p><span class="badge">{index}</span> {inline(condition)}</p></article>')


def render(source):
    text = source.read_text()
    title = text.splitlines()[0].removeprefix('# ')
    lines = text.splitlines()[1:]
    body = '<p class="muted">MD 원본의 표에서 생성합니다. 원본 수정 후 재생성하세요. 관계 화살표는 참조·전이의 방향이며 모든 실행의 성공을 뜻하지 않습니다.</p>'
    nodes = set()
    section, index, edge_count = '', 0, 0
    while index < len(lines):
        line = lines[index]
        if line.startswith('<!--') or line == f'[시각 자료]({source.stem}.html)':
            index += 1
            continue
        if line.startswith('## '):
            section = line[3:]
            body += '<h2>' + escape(section) + '</h2>'
            if section == '관계' or section == '전이':
                body += '<label for="node">관련 항목으로 좁히기</label> <select id="node"><option value="">전체</option></select><p id="count" role="status"></p>'
        elif line.startswith('|'):
            table = []
            while index < len(lines) and lines[index].startswith('|'):
                table.append(cells(lines[index])); index += 1
            if len(table) < 2:
                raise ValueError(f'표의 머리글·구분 행이 필요합니다: {source}')
            headers, rows = table[0], table[2:]
            if any(len(row) != len(headers) for row in rows):
                raise ValueError(f'표의 열 개수가 다릅니다: {source}')
            if section in {'관계', '전이'}:
                for row in rows:
                    if len(row) != 3:
                        raise ValueError(f'관계·전이는 출발·도착·조건 3열입니다: {source}')
                    nodes.update(row[:2]); edge_count += 1
                    body += relation(*row, edge_count)
            elif section == '테이블':
                body += '<div class="grid">'
                for row in rows:
                    body += '<article class="card"><h3>' + inline(row[0]) + '</h3>'
                    body += ''.join('<p><strong>' + escape(key) + '</strong><br>' + inline(value) + '</p>' for key,value in zip(headers[1:],row[1:])) + '</article>'
                body += '</div>'
            else:
                body += '<div class="table-wrap"><table><thead><tr>' + ''.join('<th>' + inline(c) + '</th>' for c in headers) + '</tr></thead><tbody>'
                body += ''.join('<tr>' + ''.join('<td>' + inline(c) + '</td>' for c in row) + '</tr>' for row in rows) + '</tbody></table></div>'
            continue
        elif line.startswith('- '):
            body += '<p>• ' + inline(line[2:]) + '</p>'
        elif line.strip():
            body += '<p>' + inline(line) + '</p>'
        index += 1
    options = ''.join('<option value="' + escape(node, quote=True) + '">' + escape(node) + '</option>' for node in sorted(nodes))
    body = body.replace('<option value="">전체</option>', '<option value="">전체</option>' + options)
    body += '<p class="muted">MD SHA-256: <code>' + sha256(text.encode()).hexdigest() + '</code></p>'
    nav = '<a href="' + source.name + '">설명·근거 MD</a> <a href="' + ('../README.md' if source.parent.name == 'states' else 'README.md') + '">문서 지도</a>'
    script = '''
const selector=document.querySelector('#node');const edges=[...document.querySelectorAll('.edge-row')];
if(selector){const filter=()=>{edges.forEach(n=>n.hidden=!!selector.value&&n.dataset.source!==selector.value&&n.dataset.target!==selector.value);document.querySelector('#count').textContent=`${edges.filter(n=>!n.hidden).length} / ${edges.length}개 관계·전이`;};selector.addEventListener('change',filter);filter();}
'''
    return page(title, nav, body, script)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args(argv)
    paths = sorted(p for p in (ROOT/'docs/design-docs').rglob('*.md')
                   if '<!-- visual: relations -->' in p.read_text())
    stale = []
    for source in paths:
        output = source.with_suffix('.html')
        html = render(source)
        if args.check:
            if not output.exists() or output.read_text() != html:
                stale.append(output.relative_to(ROOT).as_posix())
        else:
            output.write_text(html)
    for path in stale:
        print('재생성 필요:', path)
    print(f'설계 HTML {len(paths)}개, 불일치 {len(stale)}개')
    return int(bool(stale))


if __name__ == '__main__':
    raise SystemExit(main())
