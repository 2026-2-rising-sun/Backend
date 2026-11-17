"""OpenAPI YAML과 로컬 참조를 오프라인 HTML로 표시한다. 스키마 의미 검증기는 아니다."""
import argparse
from hashlib import sha256
from html import escape
import os
import json
import re
import markdown
from pathlib import Path
from urllib.parse import unquote, urlsplit

import yaml
from doc_html import page

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('commercecore', 'payment', 'catalog', 'member', 'live', 'notification')
METHODS = {'get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'}
RENDERER_VERSION = '2'


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


def module_for(route):
    """기존 공개 경로를 소유 모듈로 분류한다. 모르는 경로는 누락 대신 오류로 알린다."""
    if 'coupon' in route: return 'coupon'
    if '/cart/' in route or route.endswith('/cart'): return 'cart'
    if '/product' in route: return 'product'
    if '/sales' in route: return 'sku'
    if any(part in route for part in ('/order', '/payment', '/refund')): return 'order'
    raise ValueError('CommerceCore API의 모듈 분류가 필요합니다: ' + route)


def resolved(value, file, root, bundle):
    seen=set()
    while isinstance(value,dict) and '$ref' in value:
        ref=value['$ref']
        if (file,ref) in seen: return value,file
        seen.add((file,ref))
        target,pointer=reference(file,ref,root)
        base=bundle[target]
        for part in pointer.split('/')[1:]:
            part=part.replace('~1','/').replace('~0','~')
            base=base[int(part)] if isinstance(base,list) else base[part]
        value={**base,**{k:v for k,v in value.items() if k!='$ref'}}
        file=target
    return value,file


def cell(value):
    return escape(str(value)).replace('[','&#91;').replace(']','&#93;').replace('|','&#124;').replace('\n','<br>')


def schema_rows(schema,file,root,bundle,prefix='',depth=0):
    schema,file=resolved(schema,file,root,bundle)
    if depth>4:return []
    rows=[]
    if schema.get('type')=='array' and 'items' in schema:
        return schema_rows(schema['items'],file,root,bundle,prefix+'[]',depth+1)
    for name,field in schema.get('properties',{}).items():
        field,next_file=resolved(field,file,root,bundle)
        full=prefix+'.'+name if prefix else name
        kind=field.get('type', 'oneOf' if 'oneOf' in field else 'allOf' if 'allOf' in field else 'schema')
        limits={k:v for k,v in field.items() if k in ('format','enum','minimum','maximum','minLength','maxLength','minItems','maxItems','pattern','default','nullable','readOnly','writeOnly')}
        rows.append('| '+ ' | '.join(map(cell,(full,kind,'필수' if name in schema.get('required',[]) else '선택',field.get('description',''),json.dumps(limits,ensure_ascii=False) if limits else '—')))+' |')
        rows.extend(schema_rows(field,next_file,root,bundle,full,depth+1))
    # 조합 스키마의 필드도 표시하되 원본의 선택 의미는 원문에 보존한다.
    for key in ('allOf','oneOf','anyOf'):
        for i,part in enumerate(schema.get(key,[]),1):
            rows.extend(schema_rows(part,file,root,bundle,prefix+f'[{key} {i}]',depth+1))
    return rows


def referenced_bundle(bundle, source, selected, root):
    """선택된 API가 사용하는 참조만 닫힌 집합으로 수집한다."""
    scoped={source: {}}
    seen=set()
    def scan(node,file):
        if isinstance(node,dict):
            if '$ref' in node:
                target,pointer=reference(file,node['$ref'],root)
                if (target,pointer) not in seen:
                    seen.add((target,pointer))
                    value=bundle[target]
                    parts=[part.replace('~1','/').replace('~0','~') for part in pointer.split('/')[1:]]
                    # 참조 대상의 상위 컴포넌트를 보존해 중첩 JSON Pointer도 탐색한다.
                    keep_parts=parts[:3] if parts[:1]==['components'] else parts
                    kept=value
                    for part in keep_parts:kept=kept[int(part)] if isinstance(kept,list) else kept[part]
                    dest=scoped.setdefault(target,{})
                    for part in keep_parts[:-1]:dest=dest.setdefault(part,{})
                    if keep_parts:dest[keep_parts[-1]]=kept
                    else:scoped[target]=kept
                    scan(kept,target)
            for child in node.values():scan(child,file)
        elif isinstance(node,list):
            for child in node:scan(child,file)
    for route,method,operation,path in selected:
        scan(operation,source);scan(path.get('parameters',[]),source)
    security=bundle[source].get('components',{}).get('securitySchemes',{})
    if security:
        scoped[source].setdefault('components',{})['securitySchemes']=security
        scan(security,source)
    return scoped


def markdown_for(root,service,module=None):
    source=(root/f'contracts/api/{service}-service.yaml').resolve()
    output=root/f'docs/design-docs/services/{service}'
    if module:output/=module
    output/='api.md'
    bundle=documents(source,root);spec=bundle[source]
    title=spec['info']['title']+(f' / {module}' if module else '')
    lines=[f'# {title}', '', '<!-- generated: OpenAPI -->', '',
           f'[계약 YAML]({os.path.relpath(source,output.parent)}) · OpenAPI {spec["openapi"]} · 계약 {spec["info"]["version"]}', '',
           '계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.', '',
           (f'{module} 모듈에 속한 API를 분리해 표시합니다. 전체 계약의 공통 설명은 계약 YAML에서 확인합니다.' if module else escape(spec['info'].get('description',''))), '', '## 공통 인증과 서버', '',
           '```yaml',yaml.safe_dump({'security':spec.get('security',[]),'securitySchemes':spec.get('components',{}).get('securitySchemes',{}),'servers':spec.get('servers',[])},allow_unicode=True,sort_keys=False).rstrip(),'```','']
    selected=[]
    for route,path in spec.get('paths',{}).items():
        if module and module_for(route)!=module:continue
        for method,operation in path.items():
            if method not in METHODS:continue
            selected.append((route,method,operation,path))
            lines += [f'## {method.upper()} {route}', '', escape(operation.get('summary','')), '', escape(operation.get('description','')), '',
                      '**인증:** `'+json.dumps(operation.get('security',spec.get('security',[])),ensure_ascii=False)+'`',
                      '**태그:** '+(', '.join(operation.get('tags',[])) or '없음'), '']
            params={}
            for raw in [*path.get('parameters',[]),*operation.get('parameters',[])]:
                param,pfile=resolved(raw,source,root,bundle);params[(param['in'],param['name'])]=(param,pfile)
            if params:
                lines += ['### 요청 파라미터','','| 이름 | 위치 | 필수 | 타입 | 설명 |','|---|---|---|---|---|']
                for param,pfile in params.values():
                    ps,_=resolved(param.get('schema',{}),pfile,root,bundle)
                    lines += ['| '+' | '.join(map(cell,(param['name'],param['in'],'필수' if param.get('required') else '선택',ps.get('type','schema'),param.get('description',''))))+' |']
                lines += ['']
            if 'requestBody' in operation:
                request,rfile=resolved(operation['requestBody'],source,root,bundle)
                lines += ['### 요청 본문','',f'본문: {"필수" if request.get("required") else "선택"}', '']
                for mime,content in request.get('content',{}).items():
                    lines += [f'**{mime}**','','| 필드 | 타입 | 필수 | 설명 | 제약 |','|---|---|---|---|---|',*schema_rows(content.get('schema',{}),rfile,root,bundle),'']
                    if 'example' in content:lines += ['```json',json.dumps(content['example'],ensure_ascii=False,indent=2),'```','']
            lines += ['### 응답','','| 상태 코드 | 설명 |','|---|---|']
            for code,raw in operation.get('responses',{}).items():
                response,_=resolved(raw,source,root,bundle)
                lines += [f'| {code} | {cell(response.get("description",""))} |']
            lines += ['']
            for code,raw in operation.get('responses',{}).items():
                response,rfile=resolved(raw,source,root,bundle)
                for mime,content in response.get('content',{}).items():
                    lines += [f'#### {code} · {mime}','','| 필드 | 타입 | 필수 | 설명 | 제약 |','|---|---|---|---|---|',*schema_rows(content.get('schema',{}),rfile,root,bundle),'']
                    if 'example' in content:lines += ['```json',json.dumps(content['example'],ensure_ascii=False,indent=2),'```','']
            # 요약 표가 표현하지 않는 제약·확장·응답 헤더도 원문에서 확인할 수 있다.
            declaration={**operation,'경로 공통 선언':{k:path[k] for k in ('parameters','servers') if k in path},'적용 서버':operation.get('servers',path.get('servers',spec.get('servers',[])))}
            lines += ['### 계약 원문','','```yaml',yaml.safe_dump(declaration,allow_unicode=True,sort_keys=False).rstrip(),'```','']
    bundle=referenced_bundle(bundle,source,selected,root)
    fingerprint=sha256(b''.join(file.name.encode()+file.read_bytes() for file in sorted(bundle))).hexdigest()
    lines += ['## 전체 스키마 원본','','공통·순환 참조는 계약의 components 정의를 따릅니다.','']
    for file,doc in sorted(bundle.items()):
        lines += [f'### {file.name}','','```yaml',yaml.safe_dump({k:v for k,v in doc.items() if k!='paths'},allow_unicode=True,sort_keys=False).rstrip(),'```','']
    lines += [f'입력 SHA-256: `{fingerprint}`','']
    return output,'\n'.join(lines),bundle,source,selected


def render(root,service,module=None):
    output,md,bundle,source,selected=markdown_for(root,service,module)
    from render_design_docs import slug
    engine=markdown.Markdown(extensions=['tables','fenced_code','toc'],extension_configs={'toc':{'slugify':slug,'toc_depth':2}})
    body=engine.convert('\n'.join(md.splitlines()[1:]))
    # API 단위 접기와 도메인별 목차로 원문 상세보다 요청·응답을 먼저 읽게 한다.
    body=re.sub(r'(<table>.*?</table>)',r'<div class="table-wrap">\1</div>',body,flags=re.S)
    pieces=re.split(r'(?=<h2 id="[^"]+">(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE) )',body)
    result=pieces[0]
    heading='<h2 id="공통-인증과-서버">공통 인증과 서버</h2>'
    if heading in result:
        result=result.replace(heading,'<details><summary id="공통-인증과-서버">공통 인증과 서버</summary>')+'</details>'
    for chunk in pieces[1:]:
        tail=''
        if '<h2 id="전체-스키마-원본">' in chunk:
            chunk,tail=chunk.split('<h2 id="전체-스키마-원본">',1);tail='<h2 id="전체-스키마-원본">'+tail
        match=re.match(r'<h2 id="([^"]+)">([^<]+)</h2>',chunk)
        if not match:result+=chunk+tail;continue
        ident,label=match.groups();method=label.split()[0]
        result+=f'<details class="operation endpoint" id="{ident}"><summary><span class="badge method-{method.lower()}">{method}</span>{label[len(method):]}</summary>'+chunk[match.end():]+'</details>'+tail
    # 참조 원문 앵커는 계약 전체를 보존해 외부·순환 참조의 연결을 검증한다.
    result+='<h2>참조 탐색</h2>'
    for file,doc in sorted(bundle.items()):
        result+='<details><summary>'+escape(file.name)+'</summary>'+render_value(doc,file,root,anchors=True)+'</details>'
    result=result.replace('<h2 id="전체-스키마-원본">전체 스키마 원본</h2>', '<details class="schema-source"><summary id="전체-스키마-원본">참조 스키마 원문 펼치기</summary>')
    if '<details class="schema-source">' in result:
        result=result.replace('<h2>참조 탐색</h2>', '</details><h2>참조 탐색</h2>')
    controls='<label for="search">API 찾기</label> <input id="search" type="search" autocomplete="off" spellcheck="false" placeholder="예: /orders 또는 상품…"><p id="count" role="status"></p><div class="controls"><button id="expand" type="button">모두 펼치기</button><button id="collapse" type="button">모두 접기</button></div>'
    nav='<a href="api.md">MD 명세</a><a href="'+escape(os.path.relpath(source,output.parent))+'">계약 YAML</a>'
    home=root/'docs/README.html'
    if home.exists():nav+='<a href="'+escape(os.path.relpath(home,output.parent))+'">문서 지도</a>'
    overview=output.parent/'overview.html'
    if not overview.exists():overview=output.parent.parent/'overview.html'
    if overview.exists():nav+='<a href="'+escape(os.path.relpath(overview,output.parent))+'">서비스·모듈 개요</a>'
    return output.with_suffix('.html'),page(md.splitlines()[0][2:],nav,controls+result,API_SCRIPT,engine.toc)


API_SCRIPT="""
const operations=[...document.querySelectorAll('.operation')];
const search=document.querySelector('#search');
function filter(){const q=search.value.toLowerCase();operations.forEach(n=>n.hidden=!n.textContent.toLowerCase().includes(q));document.querySelector('#count').textContent=`${operations.filter(n=>!n.hidden).length} / ${operations.length}개 API`;}
search.addEventListener('input',filter);filter();
document.querySelector('#expand').onclick=()=>operations.filter(n=>!n.hidden).forEach(n=>n.open=true);
document.querySelector('#collapse').onclick=()=>operations.forEach(n=>n.open=false);
function reveal(){const id=decodeURIComponent(location.hash.slice(1));if(!id)return;const target=document.getElementById(id);if(!target)return;let n=target;while(n){if(n.tagName==='DETAILS')n.open=true;n=n.parentElement;}if(target.classList.contains('operation')){search.value='';filter();}target.scrollIntoView();}
window.addEventListener('hashchange',reveal);reveal();
"""


def targets():
    for service in SERVICES:
        if service=='commercecore':
            for module in ('product','sku','cart','order','coupon'):yield service,module
        else:yield service,None


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args(argv);stale=[];count=0
    for service,module in targets():
        md,content,*_=markdown_for(ROOT,service,module)
        html,rendered=render(ROOT,service,module)
        for output,value in ((md,content),(html,rendered)):
            count+=1
            if args.check:
                if not output.exists() or output.read_text()!=value:stale.append(output.relative_to(ROOT).as_posix())
            else:
                output.parent.mkdir(parents=True,exist_ok=True);output.write_text(value)
    for path in stale:print('재생성 필요:',path)
    print(f'API MD/HTML {count//2}쌍, 불일치 {len(stale)}개')
    return int(bool(stale))


if __name__=='__main__':raise SystemExit(main())
