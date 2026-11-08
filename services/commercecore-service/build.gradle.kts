plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":common-module"))
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
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

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") { enabled = false }

tasks.named<Test>("test") { outputs.dir(layout.buildDirectory.dir("contract-samples")) }
