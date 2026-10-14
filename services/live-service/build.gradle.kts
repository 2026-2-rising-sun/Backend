plugins { id("org.springframework.boot") }

dependencies {
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1")
    implementation(project(":common-module"))
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation(testFixtures(project(":common-module")))
    testImplementation("org.springframework.security:spring-security-test")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("software.amazon.awssdk:ivs:2.49.6")

    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }

// 계약 검사기가 실제 Java 직렬화 결과를 비교할 수 있도록 테스트 출력으로 보존한다.
tasks.named<Test>("test") { outputs.dir(layout.buildDirectory.dir("contract-samples")) }
