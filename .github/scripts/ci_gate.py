"""Require planned jobs to pass and permit only explicitly unplanned skips."""
import json
import os

JOBS = {"build", "postgres", "contracts", "integration"}


def verify(plan, results):
    if set(results) != JOBS | {"plan"} or results["plan"]["result"] != "success":
        raise ValueError("Verification planner failed or job set changed")
    expected = plan["jobs"]
    if set(expected) != JOBS or any(type(value) is not bool for value in expected.values()):
        raise ValueError("Missing or invalid verification plan")
    if expected["integration"] and not expected["build"]:
        raise ValueError("Integration needs application jars")
    if plan["full"] and not all(expected.values()):
        raise ValueError("Full regression plan cannot skip jobs")
    for job, enabled in expected.items():
        required = "success" if enabled else "skipped"
        if results[job]["result"] != required:
            raise ValueError(f"{job}: expected {required}, got {results[job]['result']}")


if __name__ == "__main__":
    results = json.loads(os.environ["RESULTS"])
    plan = json.loads(os.environ["PLAN_JSON"])
    verify(plan, results)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write("Verified commit: `" + os.environ["GITHUB_SHA"] + "`\n\n")
        for name, job in results.items():
            stream.write(f"- {name}: {job['result']}\n")
        stream.write("\nUnit and PostgreSQL counts overlap; they are separate evidence, not additive.\n")
