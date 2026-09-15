rootProject.name = "backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("build-logic")

include(
    "services:member-service",
    "services:shopping-service",
    "services:commerce-service",
    "services:live-service",
    "services:notification-service",
)

include(
    "libs:common-core",
    "libs:common-web",
    "libs:common-security",
    "libs:common-persistence",
    "libs:common-kafka",
    "libs:common-resilience",
)

include("contracts:events")
