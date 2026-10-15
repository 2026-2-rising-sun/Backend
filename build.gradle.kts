import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("com.diffplug.spotless") version "7.2.1"
}

allprojects {
    group = "com.shoppinglive"
    version = "0.0.1-SNAPSHOT"
}

fun Test.requireExecutedExternalTests() {
    outputs.upToDateWhen { false }
    addTestListener(
        object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}

            override fun beforeTest(test: TestDescriptor) {}

            override fun afterTest(test: TestDescriptor, result: TestResult) {}

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) {
                    check(result.testCount > 0 && result.skippedTestCount == 0L) {
                        "외부 태그 테스트는 스킵 없이 실행되어야 합니다: $name"
                    }
                }
            }
        }
    )
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
            useJUnitPlatform { excludeTags("postgres", "redis") }
            dependsOn(tasks.named("testClasses"))
            shouldRunAfter(tasks.named("test"))
            if (project.name in setOf("member-service", "notification-service")) {
                // 이 두 서비스의 일반 테스트 설정은 환경변수로 DB를 선택한다.
                val dbPrefix =
                    if (project.name == "member-service") "MEMBER_TEST_DB_"
                    else "NOTIFICATION_TEST_"
                val dbEnvironment = System.getenv().filterKeys { it.startsWith(dbPrefix) }
                inputs.properties(dbEnvironment)
                outputs.upToDateWhen { dbEnvironment.values.all { it.isBlank() } }
            }
        }
    tasks.named("check") { dependsOn(integrationTest) }

    if (path.startsWith(":services:")) {
        val validateExternalTestClasses =
            tasks.register("validateExternalTestClasses") {
                dependsOn(tasks.named("integrationTestClasses"))
                doLast {
                    check(
                        integrationSources.output.classesDirs.asFileTree.any {
                            it.extension == "class"
                        }
                    ) {
                        "외부 테스트 클래스가 없습니다: ${project.name}"
                    }
                }
            }
        val prefix = name.removeSuffix("-service").uppercase()
        val postgresUrl =
            if (prefix == "MEMBER") "MEMBER_TEST_DB_URL" else "${prefix}_TEST_POSTGRES_URL"
        val postgresEnabled = {
            if (prefix == "LIVE") System.getenv("LIVE_PG_TEST") == "1"
            else System.getenv(postgresUrl)?.startsWith("jdbc:postgresql:") == true
        }
        fun registerExternalTest(taskName: String, tag: String, enabled: () -> Boolean) =
            tasks.register<Test>(taskName) {
                description = "격리된 외부 환경에서 $tag 태그 테스트 실행"
                group = "verification"
                testClassesDirs = integrationSources.output.classesDirs
                classpath = integrationSources.runtimeClasspath
                dependsOn(validateExternalTestClasses)
                shouldRunAfter(tasks.named("test"))
                useJUnitPlatform { includeTags(tag) }
                requireExecutedExternalTests()
                doFirst { check(enabled()) { "$taskName 실행에 필요한 격리된 DB·Redis 환경이 없습니다." } }
            }
        registerExternalTest("migrationTest", "postgres & migration", postgresEnabled)
        registerExternalTest("redisTest", "redis") { System.getenv("${prefix}_REDIS_TEST") == "1" }
    }

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
