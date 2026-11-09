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
    "services:catalog-service",
    "services:commercecore-service",
    "services:payment-service",
    "services:live-service",
    "services:notification-service",
)
