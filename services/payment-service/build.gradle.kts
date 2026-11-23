plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":common-module"))
    // 루트 빌드는 implementation에만 Boot BOM을 건다. Lombok 버전도 같은 BOM에서 받는다.
    val boot = platform("org.springframework.boot:spring-boot-dependencies:3.5.16")
    compileOnly(boot)
    annotationProcessor(boot)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(testFixtures(project(":common-module")))
    testImplementation("org.springframework.security:spring-security-test")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = true }

tasks.named<Test>("test") { outputs.dir(layout.buildDirectory.dir("contract-samples")) }
