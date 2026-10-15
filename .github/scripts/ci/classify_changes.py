"""Select CI work from the diff of the commit actually checked out for testing."""
import argparse
from dataclasses import dataclass
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[3]
SERVICES = ("member-service", "shopping-service", "commerce-service", "live-service",
            "notification-service")
SPEC = importlib.util.spec_from_file_location(
    "api_structure", Path(__file__).resolve().parents[1] / "api/check_structure.py")
API = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(API)
MIGRATION_SERVICES = SERVICES
LIVE_PREFIX = "services/live-service/"
PUBSUB_CALLERS = {"ChatWriteService.java", "BroadcastEndNotifier.java", "BroadcastService.java"}


@dataclass
class Change:
    path: str
    before: bytes | None
    after: bytes | None
    same_mode: bool = True


def java_tokens(source):
    # Java processes Unicode escapes before recognizing comments. Tree-sitter does not.
    if re.search(rb"\\u+[0-9a-fA-F]{4}", source):
        raise ValueError("Unicode escapes require a build")
    tree = API.Parser(API.JAVA).parse(source)
    if tree.root_node.has_error:
        raise ValueError("Unparseable Java requires a build")
    tokens = []

    def walk(node):
        if node.type in {"line_comment", "block_comment"}:
            return
        if node.type in {"string_literal", "character_literal"} or not node.children:
            tokens.append((node.type, node.text))
        else:
            for child in node.children:
                walk(child)

    walk(tree.root_node)
    return tokens


def comments_only(change):
    if change.before is None or change.after is None or not change.same_mode:
        return False
    try:
        return java_tokens(change.before) == java_tokens(change.after)
    except ValueError:
        return False


def routes_changed(change):
    try:
        def routes(source):
            # Line numbers move when comments change; operation multiplicity matters.
            found = API.controller_routes((source or b"").decode("utf-8"), change.path)
            return {operation: len(locations) for operation, locations in found.items()}
        return routes(change.before) != routes(change.after)
    except (ValueError, UnicodeError):
        return True


def document(path):
    # Runtime resources are never documentation just because of their extension/name.
    if "/src/" in path or path.startswith("contracts/api/"):
        return False
    name = Path(path).name
    return (name == "AGENTS.md" or re.fullmatch(r"readme(?:\.(md|mdx|rst|adoc|txt))?", name, re.I)
            or Path(path).suffix.lower() in {".md", ".mdx", ".rst", ".adoc"}
            or (path.startswith("docs/") and Path(path).suffix.lower() == ".txt"))


def tags(source):
    """Read actual @Tag annotations; comments and string mentions are not tags."""
    if source is None:
        return set()
    tree = API.Parser(API.JAVA).parse(source)
    result = set()
    pending = [tree.root_node]
    while pending:
        node = pending.pop()
        if node.type == "annotation" and API.name(node) == "Tag":
            arguments = node.child_by_field_name("arguments")
            for argument in arguments.named_children:
                if argument.type == "element_value_pair":
                    argument = argument.child_by_field_name("value")
                if argument.type == "string_literal":
                    result.add(json.loads(argument.text.decode()))
        pending.extend(node.named_children)
    return result


def redis_config(source):
    result = []
    for document in API.yaml.load_all(source or b"", Loader=API.UniqueLoader):
        if not isinstance(document, dict):
            continue
        spring = document.get("spring", {})
        redis = spring.get("data", {}).get("redis")
        cache = spring.get("cache")
        if not isinstance(cache, dict) or not (
            cache.get("type") == "redis" or (cache.get("type") is None and "redis" in cache)
        ):
            cache = None
        stream = document.get("live", {}).get("stream")
        if redis is not None or cache is not None or stream is not None:
            result.append((spring.get("config"), redis, cache, stream))
    return result


def redis_changed(change):
    path = change.path
    if path.endswith(".java"):
        if comments_only(change):
            return False
        found_tags = tags(change.before) | tags(change.after)
        if "redis" in found_tags:
            return True
        if path.startswith(LIVE_PREFIX) and (
            "/stream/" in path or Path(path).name in PUBSUB_CALLERS
        ):
            return True
        # A cache annotation alone cannot establish which CacheManager backs it.
        identifiers = set()
        for source in (change.before, change.after):
            if source:
                try:
                    identifiers.update(text.decode() for kind, text in java_tokens(source)
                                       if kind in {"identifier", "type_identifier"})
                except (ValueError, UnicodeError):
                    if b"Redis" in source:
                        return True
        return bool(identifiers & {"RedisCacheManager", "RedisCacheConfiguration", "StreamRelay",
                                   "RedisMessageListenerContainer", "ChannelTopic", "RedisTemplate",
                                   "StringRedisTemplate", "RedisConnectionFactory"})
    if path.endswith((".yml", ".yaml")) and "/src/" in path:
        try:
            return redis_config(change.before) != redis_config(change.after)
        except (API.yaml.YAMLError, ValueError, TypeError, AttributeError):
            return path.startswith(LIVE_PREFIX)
    if path.endswith(".gradle.kts"):
        def redis_lines(source):
            return [line.strip() for line in (source or b"").splitlines()
                    if b"redis" in line.lower()]
        if redis_lines(change.before) != redis_lines(change.after):
            return True
    return False


def classify(changes, full_reason=None):
    builds, formats, reasons = set(), set(), []
    full, api = bool(full_reason), bool(full_reason)
    tools = {"workflow": bool(full_reason), "classifier": bool(full_reason),
             "api_tests": bool(full_reason),
             "local_auth": bool(full_reason)}
    migrations = set(MIGRATION_SERVICES) if full_reason else set()
    redis = {"live-service"} if full_reason else set()
    if full_reason:
        reasons.append({"path": "*", "action": "full", "reason": full_reason})
    for change in changes:
        path = change.path
        module = "common-module" if path.startswith("common-module/") else next(
            ("services:" + service for service in SERVICES
             if path.startswith("services/" + service + "/")), None)
        if document(path):
            action = "documentation"
        elif path.startswith(".github/workflows/"):
            tools["workflow"] = tools["classifier"] = True
            action = "workflow"
        elif path.startswith(".github/scripts/ci/"):
            tools["classifier"] = True
            action = "classifier"
        elif path.startswith(".github/scripts/api/"):
            api = tools["api_tests"] = tools["classifier"] = True
            action = "api-tool"
        elif path.startswith(".github/scripts/"):
            tools["classifier"] = True
            action = "ci-tool"
        elif path.startswith("scripts/local/auth-env."):
            tools["local_auth"] = True
            action = "local-auth-tool"
        elif path.startswith("contracts/api/"):
            api = True
            action = "api-spec"
        elif path == ".github/labeler.yml" or path.startswith(".github/ISSUE_TEMPLATE/"):
            tools["workflow"] = True
            action = "workflow"
        elif module:
            if path.endswith(".java") and comments_only(change):
                formats.add(module)
                action = "format-only"
            else:
                if module == "common-module":
                    full = api = True
                else:
                    builds.add(module)
                    if "/src/main/java/" in path and path.endswith(".java"):
                        api |= routes_changed(change)
                action = "full" if module == "common-module" else "service-build"
                service = module.removeprefix("services:")
                source_tags = (tags(change.before) | tags(change.after)) if path.endswith(".java") else set()
                if "/src/main/resources/db/migration/" in path or "migration" in source_tags:
                    migrations.update(MIGRATION_SERVICES if module == "common-module" else [service])
                if redis_changed(change):
                    redis.update(["live-service"] if module == "common-module" else [service])
        elif (path in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                       "gradlew", "gradlew.bat", ".editorconfig", ".dockerignore"}
              or path.startswith(("gradle/", "build-logic/"))):
            full = api = True
            action = "full"
        else:
            full = api = True
            action = "full-fallback"
        reasons.append({"path": path, "action": action})
    tasks = ["build"] if full else (
        [":" + module + ":build" for module in sorted(builds)]
        + [":" + module + ":spotlessCheck" for module in sorted(formats - builds)])
    redis_tasks = [":services:" + service + ":redisTest" for service in sorted(redis)]
    event_samples = full or bool(builds & {"services:commerce-service", "services:live-service"})
    return {"gradle_tasks": tasks, "run_tests": full or bool(builds), "api": api,
            "events": event_samples or tools["api_tests"], "event_samples": event_samples,
            "migration_services": sorted(migrations),
            "migration_tasks": [":services:" + service + ":migrationTest" for service in sorted(migrations)],
            "redis_tasks": redis_tasks,
            **tools, "reasons": reasons}


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def diff_changes(root, base, head):
    def entries(revision):
        result = {}
        for entry in git(root, "ls-tree", "-r", "-z", revision).split(b"\0"):
            if entry:
                metadata, name = entry.split(b"\t", 1)
                mode, kind, oid = metadata.split()
                result[name] = (mode, kind, oid)
        return result
    old, new = entries(base), entries(head)
    changes = []
    # Disabling rename detection treats moves as delete + add, covering both services.
    paths = git(root, "diff", "--no-renames", "--name-only", "-z", base, head, "--")
    for name in filter(None, paths.split(b"\0")):
        def content(tree):
            entry = tree.get(name)
            return git(root, "cat-file", "blob", entry[2].decode()) if (
                entry and entry[1] == b"blob" and entry[0] == b"100644") else None
        changes.append(Change(name.decode("utf-8"), content(old), content(new),
                              old.get(name, ())[:2] == new.get(name, ())[:2]))
    return changes


def event_plan(root, event_name, payload):
    if event_name == "workflow_dispatch":
        return classify([], "Manual full verification")
    try:
        head = git(root, "rev-parse", "HEAD").decode().strip()
        if event_name == "pull_request":
            base = payload["pull_request"]["base"]["sha"]
            if not re.fullmatch(r"[0-9a-f]{40}", base):
                raise ValueError("Invalid PR base")
            base = git(root, "merge-base", base, head).decode().strip()
        elif event_name == "push":
            base = payload["before"]
            if payload["after"] != head:
                raise ValueError("Checkout differs from push after")
        else:
            raise ValueError("Unknown event")
        if not re.fullmatch(r"[0-9a-f]{40}", base) or base == "0" * 40:
            raise ValueError("No comparison base")
        return classify(diff_changes(root, base, head))
    except (KeyError, ValueError, subprocess.CalledProcessError) as error:
        return classify([], "Comparison unavailable: " + str(error))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--event", default=os.getenv("GITHUB_EVENT_NAME"))
    parser.add_argument("--event-path", type=Path,
                        default=os.getenv("GITHUB_EVENT_PATH"))
    parser.add_argument("--output", type=Path, default=Path("build/ci/selection.json"))
    args = parser.parse_args()
    payload = json.loads(args.event_path.read_text()) if args.event_path else {}
    plan = event_plan(args.root, args.event, payload)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n")
    if os.getenv("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            for key, value in plan.items():
                if key != "reasons":
                    stream.write(key + "=" + json.dumps(value) + "\n")
    summary = "### Selected CI work\n\n```json\n" + json.dumps(
        plan, ensure_ascii=False, indent=2) + "\n```\n"
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(summary)
    print(summary)


if __name__ == "__main__":
    main()
