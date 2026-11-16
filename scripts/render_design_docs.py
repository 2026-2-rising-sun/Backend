"""흐름·서비스 설계·ADR·워크로그를 같은 주제의 MD/HTML 쌍으로 유지한다."""
import argparse
from hashlib import sha256
from html import escape, unescape
from html.parser import HTMLParser
import os
from pathlib import Path
import re
import textwrap

import markdown
from doc_html import page

ROOT = Path(__file__).resolve().parents[1]


def slug(value, separator):
    return re.sub(r'[^\w\- ]', '', value.lower()).replace(' ', '-')


def inline(text):
    return markdown.markdown(text).removeprefix('<p>').removesuffix('</p>')


class RelationTables(HTMLParser):
    """표에 명시된 출발·도착·조건만 읽고 관계를 추정하지 않는다."""
    def __init__(self):
        super().__init__()
        self.tables, self.rows, self.row, self.cell = [], None, None, None

    def handle_starttag(self, tag, attrs):
        if tag == 'table': self.rows = []
        elif tag == 'tr' and self.rows is not None: self.row = []
        elif tag in ('th', 'td') and self.row is not None: self.cell = ''

    def handle_data(self, data):
        if self.cell is not None: self.cell += data

    def handle_endtag(self, tag):
        if tag in ('th', 'td') and self.cell is not None:
            self.row.append(self.cell.strip()); self.cell = None
        elif tag == 'tr' and self.row is not None:
            self.rows.append(self.row); self.row = None
        elif tag == 'table' and self.rows is not None:
            if self.rows and self.rows[0][:2] == ['출발', '도착']:
                self.tables.append(self.rows[1:])
            self.rows = None


def relation_graph(rows, number):
    if any(len(row) != 3 for row in rows):
        raise ValueError('관계·전이는 출발·도착·조건 3열이어야 합니다.')
    nodes = list(dict.fromkeys(v for row in rows for v in row[:2]))
    if not nodes: return ''
    width = 1140
    height = ((len(nodes) + 2) // 3) * 170 + 50
    positions = {node: (30 + i % 3 * 375, 35 + i // 3 * 170) for i, node in enumerate(nodes)}
    svg = [f'<svg viewBox="0 0 {width} {height}" role="img" aria-label="전체 관계도 {number}">',
           f'<defs><marker id="arrow-{number}" markerWidth="9" markerHeight="9" refX="8" refY="4" orient="auto"><path d="M0 0L8 4L0 8Z"/></marker></defs>']
    for i, (source, target, condition) in enumerate(rows, 1):
        x,y = positions[source]; tx,ty = positions[target]
        # 그림의 번호와 아래 조건표를 연결한다. 같은 노드는 한 번만 표시한다.
        if source == target:
            path = f'M{x+160},{y} C{x+100},{y-38} {x+260},{y-38} {x+210},{y}'
            mx,my=x+180,y-20
        else:
            sx,sy=x+160,y+42; ex,ey=tx+160,ty+42
            if ty==y:
                sx=x+(320 if tx>x else 0); ex=tx+(0 if tx>x else 320)
            else:
                sy=y+(85 if ty>y else 0); ey=ty+(0 if ty>y else 85)
            path=f'M{sx},{sy} L{ex},{ey}';mx,my=(sx+ex)/2,(sy+ey)/2
        svg.append(f'<g class="edge-row" data-source="{escape(source,quote=True)}" data-target="{escape(target,quote=True)}"><title>{escape(source+" → "+target+": "+condition)}</title><path class="connector" d="{path}" marker-end="url(#arrow-{number})"/><text class="edge-number" x="{mx}" y="{my-5}">{i}</text></g>')
    for node,(x,y) in positions.items():
        lines=textwrap.wrap(node,30)[:4]
        label=''.join(f'<tspan x="{x+160}" dy="{0 if i==0 else 17}">{escape(line)}</tspan>' for i,line in enumerate(lines))
        svg.append(f'<g><title>{escape(node)}</title><rect x="{x}" y="{y}" width="320" height="85" rx="5"/><text x="{x+160}" y="{y+28}" text-anchor="middle">{label}</text></g>')
    svg.append('</svg>')
    return (f'<section class="graph"><h2>전체 관계도 {number}</h2><p class="muted">화살표 번호는 아래 관계·처리 조건표의 행 순서입니다. 배치는 실행 순서가 아닙니다.</p>'
            '<div class="controls"><button type="button" data-zoom="in">확대</button><button type="button" data-zoom="out">축소</button><button type="button" data-zoom="fit">전체 보기</button></div>'
            '<div class="diagram" tabindex="0" aria-label="관계도 가로 이동">'+''.join(svg)+'</div></section>')


def render(source):
    text=source.read_text()
    # 의미 있는 표가 파싱되지 않은 채 사라지는 것을 방지한다.
    columns=None
    fenced=False
    for line in text.splitlines():
        if line.lstrip().startswith(('```','~~~')):fenced=not fenced
        if fenced:continue
        if line.startswith('|'):
            count=len(line.strip().strip('|').split('|'))
            if columns is not None and count!=columns:
                raise ValueError(f'표의 열 개수를 확인하세요: {source}')
            columns=count
        else:columns=None
    lines=text.splitlines()
    title=lines[0].removeprefix('# ')
    content='\n'.join(lines[1:])
    content=re.sub(r'<!--.*?-->', '', content, flags=re.S)
    engine=markdown.Markdown(extensions=['tables','fenced_code','toc','sane_lists'], extension_configs={'toc':{'slugify':slug}})
    body=engine.convert(content)
    parser=RelationTables();parser.feed(body)
    graphs=''.join(relation_graph(rows,i) for i,rows in enumerate(parser.tables,1))
    # HTML 문서에서는 같은 주제의 HTML로 이동하며 원본·코드 링크는 유지한다.
    # 생성 순서와 무관하게 관리 대상인 MD는 모두 HTML 경로로 연결한다.
    managed={p.resolve() for p in sources(ROOT)}
    def managed_link(match):
        url=unescape(match[1]);path,sep,fragment=url.partition('#')
        if path and not re.match(r'^[a-zA-Z]+:',path) and (source.parent/path).resolve() in managed:
            return 'href="'+escape(path[:-3]+'.html'+(sep+fragment if sep else ''),quote=True)+'"'
        return match[0]
    body=re.sub(r'href="([^"]+)"',managed_link,body)
    body=re.sub(r'(<table>.*?</table>)',r'<div class="table-wrap">\1</div>',body,flags=re.S)
    source_url=source.name
    home=os.path.relpath(ROOT/'docs/README.html',source.parent)
    nav=f'<a href="{escape(source_url)}">MD 원본</a><a href="{escape(home)}">문서 지도</a>'
    body=graphs+body+f'<p class="muted fingerprint">MD SHA-256: <code>{sha256(text.encode()).hexdigest()}</code></p>'
    return page(title,nav,body,GRAPH_SCRIPT,engine.toc)


GRAPH_SCRIPT='''
document.querySelectorAll('.graph').forEach(graph=>{let scale=1;const svg=graph.querySelector('svg');graph.querySelectorAll('[data-zoom]').forEach(button=>button.addEventListener('click',()=>{scale=button.dataset.zoom==='fit'?1:Math.max(.5,Math.min(3,scale+(button.dataset.zoom==='in'?.25:-.25)));svg.style.width=`${scale*100}%`;}));});
'''


def sources(root):
    paths=[root/'docs/README.md']
    for folder in ('design-docs','adr','worklog'):
        paths.extend((root/'docs'/folder).rglob('*.md'))
    return sorted(p for p in paths if p.exists() and p.name!='api.md' and '<!-- html: authored -->' not in p.read_text())


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args(argv)
    stale=[]
    for source in sources(ROOT):
        output=source.with_suffix('.html');html=render(source)
        if args.check:
            if not output.exists() or output.read_text()!=html:stale.append(output.relative_to(ROOT).as_posix())
        else:output.write_text(html)
    for source in (ROOT/'docs/design-docs').rglob('*.md'):
        if '<!-- html: authored -->' not in source.read_text():continue
        output=source.with_suffix('.html')
        marker='<!-- md-sha256: '+sha256(source.read_bytes()).hexdigest()+' -->'
        if not output.exists() or marker not in output.read_text():
            stale.append(output.relative_to(ROOT).as_posix())
    for path in stale:print('재생성 또는 수작업 쌍 대조 필요:',path)
    print(f'MD/HTML {len(sources(ROOT))}쌍, 불일치 {len(stale)}개')
    return int(bool(stale))


if __name__=='__main__':raise SystemExit(main())
