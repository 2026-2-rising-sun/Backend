plugins { id("org.springframework.boot") }

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1")
    implementation(project(":common-module"))
    testImplementation(testFixtures(project(":common-module")))
    testImplementation("org.springframework.security:spring-security-test")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 버전은 spring-boot-dependencies BOM 이 관리한다. 공용 버전 카탈로그는 다른 서비스와 공유하므로
    // shopping 작업 범위에서 건드리지 않고 이 서비스 안에서만 선언한다.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }
