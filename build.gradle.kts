import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

allprojects {
    group = "com.shoppinglive"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    if (path == ":services") return@subprojects

    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging.events("failed")
    }
    val sources = extensions.getByType<SourceSetContainer>()
    val integrationSources =
        sources.create("integrationTest") {
            compileClasspath += sources["main"].output + sources["test"].output
            runtimeClasspath += sources["main"].output + sources["test"].output
        }
    configurations[integrationSources.implementationConfigurationName].extendsFrom(
        configurations["testImplementation"]
    )
    configurations[integrationSources.runtimeOnlyConfigurationName].extendsFrom(
        configurations["testRuntimeOnly"]
    )
    val integrationTest =
        tasks.register<Test>("integrationTest") {
            description = "서비스 통합 테스트 실행"
            group = "verification"
            testClassesDirs = integrationSources.output.classesDirs
            classpath = integrationSources.runtimeClasspath
            dependsOn(tasks.named("testClasses"))
            shouldRunAfter(tasks.named("test"))
            val externalTestEnvironment =
                listOf(
                    "MEMBER_TEST_DB_URL",
                    "COMMERCE_TEST_POSTGRES_URL",
                    "SHOPPING_TEST_POSTGRES_URL",
                    "NOTIFICATION_TEST_POSTGRES_URL",
                    "NOTIFICATION_TEST_DB_DRIVER",
                    "NOTIFICATION_TEST_DB_USER",
                    "NOTIFICATION_TEST_DB_PASSWORD",
                    "LIVE_PG_TEST",
                    "LIVE_REDIS_TEST",
                    "LIVE_PG_PORT",
                    "LIVE_REDIS_PORT",
                )
            inputs.properties(externalTestEnvironment.associateWith { System.getenv(it).orEmpty() })
            outputs.upToDateWhen {
                externalTestEnvironment.none { !System.getenv(it).isNullOrBlank() }
            }
        }
    tasks.named("check") { dependsOn(integrationTest) }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.28.0").aosp()
            removeUnusedImports()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktfmt("0.54").kotlinlangStyle()
        }
    }
}

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.54").kotlinlangStyle()
    }
}
