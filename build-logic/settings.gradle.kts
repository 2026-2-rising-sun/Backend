pluginManagement {
    repositories {
        gradlePluginPortal()
        // Included builds do not inherit the root build's plugin repositories.
        mavenCentral()
    }
}

rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
