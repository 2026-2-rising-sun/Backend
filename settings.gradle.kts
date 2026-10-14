rootProject.name = "backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement { repositories { mavenCentral() } }

include(
    "common-module",
    "services:member-service",
    "services:shopping-service",
    "services:commerce-service",
    "services:live-service",
    "services:notification-service",
)
