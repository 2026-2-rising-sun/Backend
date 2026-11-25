plugins { id("org.springframework.boot") }

dependencies {
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

    // 상품 검색 색인. ES 클라이언트 버전은 BOM 이 관리하고, ShedLock 은 BOM 에 없어 직접 고정한다.
    implementation("co.elastic.clients:elasticsearch-java")
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.10.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0")

    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }
