"""저장소 문서용 오프라인 HTML 공통 화면. 외부 자산을 로드하지 않는다."""
from html import escape

STYLE = '''
:root{color-scheme:light dark;--bg:#f4f6fa;--paper:#fff;--ink:#172b45;--muted:#52647a;--line:#d7e0eb;--accent:#245dc5;--soft:#edf3ff}
@media(prefers-color-scheme:dark){:root{--bg:#101925;--paper:#1a2738;--ink:#ecf3ff;--muted:#adc0d8;--line:#354963;--accent:#8db6ff;--soft:#213552}}
*{box-sizing:border-box}[id^="ref-"]{scroll-margin-top:70px}body{margin:0;font:15px/1.7 system-ui,sans-serif;background:var(--bg);color:var(--ink)}main{max-width:1240px;margin:auto;padding:38px 26px}h1{font-size:30px;line-height:1.3}h2{margin-top:32px}h3{margin:0 0 10px}a{color:var(--accent)}code,pre{font:13px/1.6 ui-monospace,monospace}code{overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere}p{max-width:100ch}.muted,small{color:var(--muted)}.badge{display:inline-block;background:var(--soft);border-radius:6px;padding:3px 9px;margin:3px;font-size:12px}.card{background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:20px;margin:15px 0;overflow-wrap:anywhere}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px}.grid .card{margin:0}nav,.controls{display:flex;gap:14px;flex-wrap:wrap;align-items:center;margin:14px 0}button,input,select{font:inherit;color:var(--ink);background:var(--paper);border:1px solid var(--line);border-radius:6px;padding:8px 12px}input{width:min(100%,550px)}select{max-width:100%;min-width:0}:focus-visible{outline:3px solid var(--accent);outline-offset:3px}summary{cursor:pointer;padding:8px 0}details{margin:8px 0}dl{margin:10px 0;border-left:2px solid var(--line);padding-left:14px}dt{font-weight:650;overflow-wrap:anywhere}dd{margin:3px 0 13px 12px;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse}th,td{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:10px;overflow-wrap:anywhere}.table-wrap{overflow-x:auto}.diagram{overflow:auto;background:var(--paper);border:1px solid var(--line);border-radius:12px;padding:10px}.diagram svg{display:block;width:100%;min-width:560px;height:auto}.diagram text{fill:var(--ink);font:12px ui-monospace,monospace}.diagram rect{fill:var(--soft);stroke:var(--accent)}.diagram path{fill:none;stroke:var(--muted);stroke-width:1.5}.diagram .node{cursor:pointer}.edge-row[hidden],.operation[hidden]{display:none}.transition{display:grid;grid-template-columns:1fr 30px 1fr;align-items:center;gap:8px}.transition strong{padding:8px;background:var(--soft);border-radius:6px;overflow-wrap:anywhere}.transition p{grid-column:1/-1;margin:5px 0}footer{margin-top:30px;color:var(--muted);font-size:13px}@media(max-width:640px){main{padding:24px 14px}h1{font-size:25px}.card{padding:14px}.grid{grid-template-columns:1fr}th,td{padding:7px}}
'''


def page(title, navigation, body, script=''):
    return ('<!doctype html>\n<html lang="ko"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{escape(title)}</title><style>{STYLE}</style></head><body><main>'
            f'<nav aria-label="문서 이동">{navigation}</nav><h1>{escape(title)}</h1>{body}'
            '<footer>저장소 원본에서 생성한 설명 자료입니다. 실제 환경의 실행 결과는 워크로그에서 확인하세요.</footer>'
            f'</main><script>{script}</script></body></html>\n')
