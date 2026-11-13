"""Git 추적 대상 문서의 로컬 링크와 요구사항 상태·검증 참조를 점검한다."""
import argparse
from html.parser import HTMLParser
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
INLINE_LINK = re.compile(r'!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^\s)]+)(?:\s+["\'][^\n]*?["\'])?\s*\)')
DEFINITION = re.compile(r'^ {0,3}\[([^\]\n]+)\]:\s*(<[^>\n]+>|\S+)', re.M)
REFERENCE = re.compile(r'!?\[([^\]\n]+)\]\[([^\]\n]*)\]')


class HtmlReferences(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.anchors = set()

    def handle_starttag(self, tag, attrs):
        for key, value in attrs:
            if value and key in {"href", "src"}:
                self.links.append(value)
            if value and (key == "id" or (tag == "a" and key == "name")):
                self.anchors.add(value)


def prose(text, keep_inline=False):
    """コード例とコメントに書かれた仮のリンクを検査から除く。"""
    lines, fence = [], None
    for line in text.splitlines():
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if match:
            marker = match[1]
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            continue
        if fence is None:
            lines.append(line)
    text = re.sub(r"<!--.*?-->", "", "\n".join(lines), flags=re.S)
    return text if keep_inline else re.sub(r"(`+).*?\1", "", text, flags=re.S)


def links(text, html=False):
    if not html:
        text = prose(text)
    parsed = HtmlReferences()
    parsed.feed(text)
    if html:
        return parsed.links
    definitions = {label.casefold(): target.strip("<>") for label, target in DEFINITION.findall(text)}
    found = [match[1].strip("<>") for match in INLINE_LINK.finditer(text)]
    found.extend(definitions.values())
    for label, key in REFERENCE.findall(text):
        key = (key or label).casefold()
        if key not in definitions:
            raise ValueError(f"참조 링크 정의 없음: {key}")
    # 축약 참조는 정의가 있는 이름만 링크로 취급한다.
    found.extend(parsed.links)
    return found


def anchors(path):
    text = path.read_text(encoding="utf-8")
    parsed = HtmlReferences()
    parsed.feed(text if path.suffix.lower() == ".html" else prose(text))
    found, counts = parsed.anchors, {}
    if path.suffix.lower() == ".md":
        titles = re.findall(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$", prose(text, keep_inline=True), re.M)
        for title in titles:
            slug = re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-")
            count = counts.get(slug, 0)
            counts[slug] = count + 1
            found.add(slug + (f"-{count}" if count else ""))
    return found


def local_target(root, source, url):
    parsed = urlsplit(url)
    if parsed.scheme or parsed.netloc:
        return None
    relative = unquote(parsed.path)
    target = ((root / relative.lstrip("/")) if relative.startswith("/") else
              (source.parent / relative if relative else source)).resolve()
    if not target.is_relative_to(root):
        raise ValueError(f"저장소 밖 로컬 경로: {url}")
    return target, unquote(parsed.fragment)


def check_requirement(root, source, text):
    """참조와 상태의 구조만 검사한다. 실제 검증의 적절성·실행 여부는 리뷰한다."""
    errors = []
    status = re.search(r"^상태:\s*(.+)$", text, re.M)
    verification = re.search(r"^검증:\s*(.+)$", text, re.M)
    evidence = re.search(r"^근거:\s*(.+)$", text, re.M)
    if not status or status[1].strip() not in {"초안", "진행 중", "완료", "보류"}:
        errors.append("요구사항 상태는 초안·진행 중·완료·보류 중 하나여야 합니다.")
    if not verification or not links(verification[1]):
        errors.append("요구사항의 검증: 항목에 테스트 또는 검증 계획의 로컬 링크가 필요합니다.")
    elif not any(target and target[0].is_file()
                 for url in links(verification[1])
                 if (target := local_target(root, source, url))):
        errors.append("검증 참조에는 저장소 안의 테스트·방법·계획 파일을 연결하세요.")
    if status and status[1].strip() == "완료":
        method = re.search(r"^검증 방식:\s*(자동|수동|혼합)\s*$", text, re.M)
        if not method:
            errors.append("완료한 요구사항에는 검증 방식: 자동·수동·혼합 중 하나를 적으세요.")
        evidence_links = [local_target(root, source, url) for url in links(evidence[1])] if evidence else []
        if not any(target and target[0].is_file() and target[0].suffix == '.md'
                   and target[0].is_relative_to(root / "docs/worklog")
                   for target in evidence_links):
            errors.append("완료한 요구사항의 근거: 항목에 일일 작업 기록을 연결하세요.")
        if re.search(r"^\s*[-*+] \[ \]", prose(text), re.M):
            errors.append("완료한 요구사항에 미완료 수용 기준이 남아 있습니다.")
        if not re.search(r"^\s*[-*+] \[[xX]\]", prose(text), re.M):
            errors.append("완료한 요구사항에는 확인한 수용 기준 체크박스가 필요합니다.")
    return errors


def check_documents(root, paths, tracked=None):
    errors = []
    anchor_cache = {}
    for relative in sorted(paths):
        source = root / relative
        try:
            text = source.read_text(encoding="utf-8")
            for url in links(text, source.suffix.lower() == ".html"):
                target = local_target(root, source, url)
                if target is None:
                    continue
                path, fragment = target
                if not path.exists():
                    errors.append(f"{relative}: 로컬 링크 없음: {url}")
                elif tracked is not None and path.is_file() and path.relative_to(root).as_posix() not in tracked:
                    errors.append(f"{relative}: Git 추적 대상이 아닌 링크: {url}")
                elif fragment and path.suffix.lower() in {".md", ".html"}:
                    if path not in anchor_cache:
                        anchor_cache[path] = anchors(path)
                    if fragment not in anchor_cache[path]:
                        errors.append(f"{relative}: 앵커 없음: {url}")
            if relative.startswith("docs/requirements/") and source.name != "README.md":
                errors.extend(f"{relative}: {error}" for error in check_requirement(root, source, prose(text)))
        except (OSError, UnicodeError, ValueError) as error:
            errors.append(f"{relative}: {error}")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="점검할 Git 저장소")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        output = subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard", "-z"])
        tracked = {path for path in output.decode().split("\0") if path and (root / path).is_file()}
        paths = {path for path in tracked if Path(path).suffix.lower() in {".md", ".html"}
                 and "/src/" not in path}
        errors = check_documents(root, paths, tracked)
    except (OSError, subprocess.CalledProcessError, UnicodeError) as error:
        print(f"문서 점검 준비 실패: {error}")
        return 1
    for error in errors:
        print(error)
    print(f"문서 {len(paths)}개 점검, 오류 {len(errors)}개")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
