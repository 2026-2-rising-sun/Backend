"""로컬 변경에 맞는 기존 검사를 실행한다. 소스·Git 상태는 수정하지 않는다."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location(
    "harness_ci", ROOT / ".github/scripts/ci/classify_changes.py")
CI = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CI
SPEC.loader.exec_module(CI)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def collect_changes(root, base=None):
    if git(root, "ls-files", "--unmerged", "-z"):
        raise ValueError("충돌을 먼저 해결해야 합니다.")
    head = git(root, "rev-parse", "HEAD").decode().strip()
    baseline = git(root, "merge-base", "--", base, head).decode().strip() if base else head
    tree = {}
    for entry in git(root, "ls-tree", "-r", "-z", baseline).split(b"\0"):
        if entry:
            metadata, path = entry.split(b"\t", 1)
            tree[os.fsdecode(path)] = metadata.split()
    index = {}
    for entry in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if entry:
            metadata, path = entry.split(b"\t", 1)
            index[os.fsdecode(path)] = metadata.split()[:2]
    paths = set()
    for args in (("diff", "--no-renames", "--name-only", "-z", baseline, "--"),
                 ("diff", "--cached", "--no-renames", "--name-only", "-z", baseline, "--"),
                 ("ls-files", "--others", "--exclude-standard", "-z")):
        paths.update(os.fsdecode(p) for p in git(root, *args).split(b"\0") if p)
    changes = []
    for path in sorted(paths):
        old = tree.get(path)
        staged = index.get(path)
        # submodule은 내부 파일을 읽지 않고 보수적으로 전체 검사 대상으로 넘긴다.
        before = git(root, "cat-file", "blob", old[2].decode()) if old and old[1] == b"blob" else None
        file = root / path
        if file.is_symlink():
            after, mode = os.fsencode(os.readlink(file)), b"120000"
        elif file.is_file():
            after = file.read_bytes()
            mode = b"100755" if file.stat().st_mode & 0o111 else b"100644"
        else:
            after, mode = None, None
        changes.append(CI.Change(path, before, after, bool(old and old[0] == mode)))
        # 작업 트리에서 되돌렸어도 staged 변경의 검사 선택을 놓치지 않는다.
        if staged and staged[0] in (b"100644", b"100755", b"120000"):
            staged_content = git(root, "cat-file", "blob", staged[1].decode())
            if staged_content != after or staged[0] != mode:
                changes.append(CI.Change(path, before, staged_content, bool(old and old[0] == staged[0])))
    return head, baseline, changes


def fingerprint(changes):
    digest = hashlib.sha256()
    for change in changes:
        for value in (os.fsencode(change.path), change.before, change.after,
                      str(change.same_mode).encode()):
            digest.update(b"null" if value is None else hashlib.sha256(value).digest())
    return digest.hexdigest()


def commands_for(plan):
    checks = []

    def add(name, command, external=None):
        checks.append({"name": name, "command": command, "external": external})

    suites = (("classifier", ".github/scripts/ci"),
              ("api_tests", ".github/scripts/api"), ("harness", "harness"))
    for flag, directory in suites:
        if plan.get(flag):
            add(flag, [sys.executable, "-m", "unittest", "discover", "-s", directory, "-p", "test_*.py"])
    if plan["workflow"]:
        add("workflow", ["actionlint"])
    if plan["local_auth"]:
        add("local-auth", ["node", "--test", "scripts/local/auth-env.test.cjs"])
    if plan["api"]:
        add("api", [sys.executable, ".github/scripts/api/check_structure.py"])
    if plan["events"]:
        add("events", [sys.executable, ".github/scripts/api/check_events.py"])
    options = ["--no-daemon", "--max-workers=2", "--console=plain"]
    if plan["gradle_tasks"]:
        add("gradle", ["./gradlew", *plan["gradle_tasks"], *options])
    if plan["event_samples"]:
        add("event-samples", [sys.executable, ".github/scripts/api/check_events.py", "--samples"])
    for task in plan["migration_tasks"] + plan["redis_tasks"]:
        add(task, ["./gradlew", task, *options], task)
    return checks


def external_ready(task, environment):
    prefix = task.split(":")[2].removesuffix("-service").upper()
    if task.endswith(":redisTest"):
        return environment.get(prefix + "_REDIS_TEST") == "1"
    if prefix == "LIVE":
        return environment.get("LIVE_PG_TEST") == "1"
    key = "MEMBER_TEST_DB_URL" if prefix == "MEMBER" else prefix + "_TEST_POSTGRES_URL"
    return environment.get(key, "").startswith("jdbc:postgresql:")


def expected_test_tasks(command):
    """실행기가 선택하는 build·테스트 명령에서 필요한 테스트 작업만 구한다."""
    if command[0] != "./gradlew":
        return set()
    expected = set()
    for argument in command[1:]:
        module, _, task = argument.rpartition(":")
        if task == "build":
            modules = [module] if module else (
                [":common-module"] + [":services:" + service for service in CI.SERVICES])
            expected.update(m + ":" + t for m in modules for t in ("test", "integrationTest"))
        elif task in ("test", "integrationTest", "migrationTest", "redisTest"):
            expected.add(argument)
    return expected


def configured_test_database(command, environment):
    tasks = expected_test_tasks(command)
    services = [service for service in CI.SERVICES
                if any(task.startswith(":services:" + service + ":") for task in tasks)]
    for service in services:
        prefix = service.removesuffix("-service").upper()
        if any(value and key.startswith(prefix + "_") and "TEST" in key
               and key.endswith(("DB_URL", "POSTGRES_URL"))
               for key, value in environment.items()):
            return True
    return False


def test_results(root, output):
    results = []
    for match in re.finditer(r"^> Task (:(?:[\w-]+:)*)(test|integrationTest|migrationTest|redisTest)(?=\s|$)([^\n]*)", output, re.M):
        module, task, state = match.groups()
        folder = root.joinpath(*module.strip(":").split(":"), "build", "test-results", task)
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        files = [] if "NO-SOURCE" in state or "SKIPPED" in state else list(folder.glob("TEST-*.xml"))
        for file in files:
            suite = ET.parse(file).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, "0"))
        results.append({"task": module + task, "state": state.strip() or "EXECUTED",
                        "reports": len(files), **counts})
    return results


def run_checks(root, checks, external=False):
    directory = root / "build/harness"
    directory.mkdir(parents=True, exist_ok=True)
    results = []
    gradle_ok = True
    for number, check in enumerate(checks):
        result = {**check, "status": "passed"}
        if check["external"] and (not external or not external_ready(check["external"], os.environ)):
            result.update(status="not-run", reason="격리 환경을 확인한 뒤 --external과 연결 설정을 지정해야 합니다.")
        elif not external and configured_test_database(check["command"], os.environ):
            result.update(status="not-run", reason="테스트 DB 설정이 주입돼 있습니다. 격리 환경 확인 후 --external을 사용하세요.")
        elif check["name"] == "event-samples" and not gradle_ok:
            result.update(status="not-run", reason="현재 코드의 표본 생성 검사가 성공하지 않았습니다.")
        else:
            log = directory / f"{number:02d}.log"
            print("실행:", " ".join(check["command"]), flush=True)
            try:
                with log.open("w") as stream:
                    process = subprocess.run(check["command"], cwd=root, stdout=stream, stderr=subprocess.STDOUT)
                result.update(returncode=process.returncode, log=str(log.relative_to(root)))
                output = log.read_text(errors="replace")
                result["tests"] = test_results(root, output) if check["command"][0] == "./gradlew" else []
                if process.returncode:
                    result["status"] = "failed"
                elif any(t["failures"] or t["errors"] for t in result["tests"]):
                    result["status"] = "failed"
                elif (expected_test_tasks(check["command"]) - {t["task"] for t in result["tests"]}
                      or any(t["skipped"] or t["state"] == "SKIPPED"
                         or (t["state"] != "NO-SOURCE" and not t["reports"])
                         or (t["reports"] and not t["tests"]) for t in result["tests"])):
                    result.update(status="incomplete", reason="테스트 스킵·0건·결과 누락을 확인하세요.")
            except OSError:
                result.update(status="not-run", reason="명령을 시작할 수 없습니다. 도구와 실행 권한을 확인하세요.")
            except (ET.ParseError, ValueError):
                result.update(status="failed", reason="테스트 결과 XML을 읽을 수 없습니다.")
            if check["external"] and result["status"] == "passed" and (
                    not any(t["tests"] > 0 for t in result.get("tests", []))
                    or any(t["state"] != "EXECUTED" for t in result.get("tests", []))):
                result.update(status="incomplete", reason="외부 테스트의 실제 실행 결과가 없습니다.")
        if check["name"] == "gradle":
            gradle_ok = result["status"] == "passed"
        print(f"{result['name']}: {result['status']}" + (" — " + result["reason"] if "reason" in result else ""), flush=True)
        results.append(result)
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="검사할 Git 저장소")
    parser.add_argument("--base", help="이 ref와 HEAD의 merge-base부터 현재 작업 트리까지 비교")
    parser.add_argument("--dry-run", action="store_true", help="검사 목록만 출력, 파일·명령 실행 없음")
    parser.add_argument("--external", action="store_true", help="격리 환경 확인 후 선택된 외부 검사를 실행")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        head, baseline, changes = collect_changes(root, args.base)
        plan = CI.classify(changes)
        checks = commands_for(plan)
        print(json.dumps({"head": head, "base": baseline, "selection": plan,
                          "checks": checks}, ensure_ascii=False, indent=2), flush=True)
        if args.dry_run:
            return 0
        if not changes:
            print("변경 없음: 검사를 실행하지 않았습니다.")
        before = fingerprint(changes)
        results = run_checks(root, checks, args.external) if changes else []
        current_head, current_base, current_changes = collect_changes(root, args.base)
        changed = (head, baseline, before) != (current_head, current_base, fingerprint(current_changes))
        status = "failed" if any(r["status"] == "failed" for r in results) else (
            "incomplete" if changed or any(r["status"] != "passed" for r in results) else "passed")
        if not changes:
            status = "no-changes"
        report = {"head": head, "base": baseline, "fingerprint": before, "status": status,
                  "changed_during_checks": changed, "selection": plan, "results": results}
        destination = root / "build/harness/result.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        if changes and not checks:
            print("자동 검사 대상 없음: 문서·의미·가독성은 직접 확인하세요.")
        if changed:
            print("검사 중 변경이 발생했습니다. 바뀐 부분을 다시 검증하세요.")
        print(f"결과: {status} — {destination}")
        return {"passed": 0, "no-changes": 0, "failed": 1, "incomplete": 2}[status]
    except (subprocess.CalledProcessError, OSError, ValueError):
        print("검사 준비 실패: 비교 기준·Git 상태·파일 접근을 확인하세요.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
