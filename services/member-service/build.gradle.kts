plugins {
    id("shoppinglive.spring-boot-service-conventions")
    id("shoppinglive.module-boundary-conventions")
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("postgres") } }

tasks.register<Test>("postgresTest") {
    description = "Run Member API, Flyway and concurrency tests against an isolated PostgreSQL database"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    outputs.upToDateWhen { false }
    doFirst {
        require(System.getenv("MEMBER_TEST_DB_URL")?.startsWith("jdbc:postgresql:") == true) {
            "postgresTest requires MEMBER_TEST_DB_URL pointing to a disposable PostgreSQL database"
        }
    }
}

dependencies {
    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-security"))
    implementation(project(":libs:common-persistence"))
    implementation(project(":libs:common-resilience"))
    implementation(project(":contracts:events"))
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)

    testImplementation(testFixtures(project(":libs:common-security")))
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly(libs.h2)
}
