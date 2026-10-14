plugins { id("org.springframework.boot") }

tasks.named<Test>("integrationTest") { useJUnitPlatform { excludeTags("postgres") } }

tasks.register<Test>("postgresTest") {
    description =
        "Run Member API, Flyway and concurrency tests against an isolated PostgreSQL database"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    outputs.upToDateWhen { false }
    doFirst {
        require(System.getenv("MEMBER_TEST_DB_URL")?.startsWith("jdbc:postgresql:") == true) {
            "postgresTest requires MEMBER_TEST_DB_URL pointing to a disposable PostgreSQL database"
        }
    }
}

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1")
    implementation(project(":common-module"))
    testImplementation(testFixtures(project(":common-module")))
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }
