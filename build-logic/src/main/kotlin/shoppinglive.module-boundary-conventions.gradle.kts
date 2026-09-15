import org.gradle.api.artifacts.ProjectDependency

// Services must stay independently deployable: no service may depend on another service's
// module. Only the shared event contracts and the generic common libraries are reachable.
val allowedProjectPaths = listOf(":contracts:events")
val allowedProjectPathPrefixes = listOf(":libs:common-")

afterEvaluate {
    val violations = configurations.flatMap { configuration ->
        configuration.dependencies
            .filterIsInstance<ProjectDependency>()
            .map { configuration.name to it.path }
    }.filterNot { (_, path) ->
        path in allowedProjectPaths || allowedProjectPathPrefixes.any { path.startsWith(it) }
    }.distinct()

    if (violations.isNotEmpty()) {
        val details = violations.joinToString("\n") { (configuration, path) ->
            "  - $configuration -> project(\"$path\")"
        }
        throw GradleException(
            """
            Module boundary violation in ${project.path}.

            Forbidden project dependencies:
            $details

            A service may only depend on:
              - ${allowedProjectPaths.joinToString(", ")}
              - ${allowedProjectPathPrefixes.joinToString(", ") { "$it*" }}

            Cross-service communication goes through REST (see :libs:common-resilience) or
            Kafka events (see :contracts:events), never through a compile-time dependency.
            See docs/decisions/0001-record-architecture-decisions.md.
            """.trimIndent(),
        )
    }
}
