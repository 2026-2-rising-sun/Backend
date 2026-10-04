"""Select application verification separately from CI/tool self-checks."""
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

SERVICES = ("member", "shopping", "commerce", "live", "notification")
HTTP_SERVICES = SERVICES[:4]
PG = {
    "member": dict(database="member_test", task=":services:member-service:postgresTest",
                   report="services/member-service/build/test-results/postgresTest/TEST-*.xml",
                   suites="*.MemberPostgresTest *.MemberLoginPostgresTest *.MemberSessionPostgresTest",
                   minimums="*.MemberPostgresTest=1 *.MemberLoginPostgresTest=5 *.MemberSessionPostgresTest=16"),
    "commerce": dict(database="commerce_test", task=":services:commerce-service:postgresCommerceTest",
                     report="services/commerce-service/build/test-results/postgresCommerceTest/TEST-*.xml",
                     suites=" ".join("*." + name for name in (
                         "OrderCreationConcurrencyTest", "OrderCancellationConcurrencyTest", "SalesStockConcurrencyTest",
                         "SalesStatusConcurrencyTest", "PaymentFlowIntegrationTest", "PaymentSchedulingAfterCommitTest",
                         "PaymentDelayReconcilerTest", "OrderExpirationSchedulerTest", "CartOrderIntegrationTest",
                         "MemberCommerceApiTest", "MemberCommerceMigrationPostgresTest")),
                     minimums="*.MemberCommerceMigrationPostgresTest=3"),
    "live": dict(database="live_test", task=":services:live-service:test",
                 report="services/live-service/build/test-results/test/TEST-*PostgresMigrationTest.xml",
                 suites="*.PostgresMigrationTest", minimums="*.PostgresMigrationTest=4"),
}


def changed_paths(base, head, merge_base=True):
    if not all(re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (base, head)):
        raise ValueError("PR base/head must be commit SHAs")
    comparison = f"{base}{'...' if merge_base else '..'}{head}"
    raw = subprocess.check_output(["git", "diff", "--name-status", "-z", "--find-renames", comparison, "--"])
    return parse_changes(raw)


def parse_changes(raw):
    # Name-status -z keeps whitespace/newlines literal and exposes both sides of renames.
    if raw and not raw.endswith(b"\0"):
        raise ValueError("Truncated git diff")
    tokens, paths = raw.split(b"\0")[:-1], []
    while tokens:
        status = tokens.pop(0).decode("ascii")
        if not re.fullmatch(r"[ACDMRTUXB][0-9]*", status):
            raise ValueError("Unexpected git change status")
        count = 2 if status[0] in "RC" else 1
        if len(tokens) < count:
            raise ValueError("Missing changed filename")
        for _ in range(count):
            path = os.fsdecode(tokens.pop(0))
            if not path or path.startswith("/") or ".." in PurePosixPath(path).parts:
                raise ValueError("Invalid changed path")
            paths.append(path)
    return paths


def plan_changes(paths, full=False):
    selected, contracts, domains, contract_services = set(), False, set(), set()
    guards, tool_tests = False, False
    for path in paths:
        suffix = PurePosixPath(path).suffix.lower()
        if suffix in {".md", ".mdx", ".rst", ".adoc"} or (
            suffix == ".txt" and (path.startswith("docs/") or PurePosixPath(path).name.startswith("README"))
        ) or (path.startswith(".github/ISSUE_TEMPLATE/") and suffix in {".yaml", ".yml"}) or path == ".github/CODEOWNERS":
            continue
        if path.startswith((".github/workflows/", ".github/scripts/")) or path in {
            ".github/labeler.yml", ".github/dependabot.yml"
        }:
            guards = True
        elif path.startswith(("scripts/contracts/", "scripts/integration/")) or (
            path.startswith("scripts/local/") and path.endswith(".test.cjs")
        ):
            # Verification tooling is checked without starting the application.
            guards, tool_tests = True, True
        elif path.startswith(("libs/", "build-logic/", "gradle/", "contracts/events/")):
            full = True
        elif path.startswith("services/"):
            service = path.split("/", 2)[1].removesuffix("-service")
            if service not in SERVICES or path.split("/", 2)[1] != service + "-service":
                full = True
            else:
                selected.add(service)
                # Test-only changes do not change the deployed HTTP application.
                if service in HTTP_SERVICES and not path.startswith(f"services/{service}-service/src/test/"):
                    domains.add(service)
        elif path.startswith("contracts/api/"):
            contracts = True
            service = PurePosixPath(path).name.removesuffix("-service.yaml")
            if path == f"contracts/api/{service}-service.yaml" and service in HTTP_SERVICES:
                domains.add(service)
                contract_services.add(service)
            else:
                full = True
        elif path.startswith(("contracts/scenarios/", "contracts/exports/")) or path == "contracts/docs/change-review.json":
            contracts = True
            contract_services.update(HTTP_SERVICES)
        elif path.startswith("scripts/local/"):
            contracts = True
            domains.update(HTTP_SERVICES)
            contract_services.update(HTTP_SERVICES)
        else:
            # New runtime/build/config paths need review rather than a silent CI exemption.
            full = True
    if full:
        selected, contracts, domains, contract_services = set(SERVICES), True, set(HTTP_SERVICES), set(HTTP_SERVICES)
        guards = True
    integration = bool(domains)
    # Member/session authority is shared by every HTTP service. Mixed-domain changes
    # also retain the full suite, rather than dropping one side of an interaction.
    scope = next(iter(domains)) if len(domains) == 1 and "member" not in domains else "full"
    if not integration:
        scope = "none"
    selected = [service for service in SERVICES if service in selected]
    postgres = [dict(service=service, **PG[service]) for service in selected if service in PG]
    tasks = ["build"] if full else [f":services:{service}-service:build" for service in selected]
    # Live PostgreSQL cases run in its ordinary test task with LIVE_PG_TEST enabled.
    # Other database suites share the same compilation/Gradle invocation.
    tasks += [PG[service]["task"] for service in selected if service in PG and service != "live"]
    return dict(full=full, guards=guards, tool_tests=tool_tests, services=selected, tasks=tasks, integration_scope=scope,
                contract_services=[service for service in HTTP_SERVICES if service in contract_services],
                jobs=dict(build=bool(tasks), contracts=contracts, integration=integration),
                postgres=bool(postgres), postgres_cases=postgres)


def plan_push(paths):
    plan = plan_changes(paths)
    # Application changes retain the branch-wide regression before publication.
    # CI/doc-only pushes cannot publish unverified application images.
    return plan_changes(paths, full=True) if any(plan["jobs"].values()) else plan


def main():
    event_name = os.environ["GITHUB_EVENT_NAME"]
    if event_name == "pull_request":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        pr = event["pull_request"]
        plan = plan_changes(changed_paths(pr["base"]["sha"], pr["head"]["sha"]))
    elif event_name == "push":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        plan = plan_changes([], full=True) if event["before"] == "0" * 40 else plan_push(
            changed_paths(event["before"], event["after"], merge_base=False))
    elif event_name == "workflow_dispatch":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        scope = event.get("inputs", {}).get("scope", "full")
        if scope not in (*SERVICES, "full"):
            raise ValueError("Unknown manual verification scope")
        plan = plan_changes([] if scope == "full" else [f"services/{scope}-service/src/main/"], full=scope == "full")
    else:
        raise ValueError("Unsupported CI event")
    outputs = {"plan": json.dumps(plan, separators=(",", ":")), "full": str(plan["full"]).lower(),
               "guards": str(plan["guards"]).lower(), "tool_tests": str(plan["tool_tests"]).lower(),
               "services": json.dumps(plan["services"]), "tasks": " ".join(plan["tasks"]),
               "integration_scope": plan["integration_scope"], "contract_services": json.dumps(plan["contract_services"]),
               "postgres": str(plan["postgres"]).lower(), "postgres_cases": json.dumps(plan["postgres_cases"]),
               **{job: str(enabled).lower() for job, enabled in plan["jobs"].items()}}
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        for key, value in outputs.items():
            stream.write(f"{key}={value}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write("### Verification plan\n```json\n" + json.dumps(plan, indent=2) + "\n```\n")


if __name__ == "__main__":
    main()
