import java.util.UUID

plugins { id("org.springframework.boot") }

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.1")
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

// A fresh schema per invocation isolates destructive test fixtures from any existing application
// data.
val postgresTestSchema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "")
val cleanupPostgresCommerceTest =
    tasks.register<JavaExec>("cleanupPostgresCommerceTest") {
        group = "verification"
        classpath = sourceSets["integrationTest"].runtimeClasspath
        mainClass.set("com.shoppinglive.commerce.support.PostgresSchemaCleanup")
        systemProperty("commerce.tests.schema", postgresTestSchema)
    }

tasks.register<Test>("postgresCommerceTest") {
    group = "verification"
    description =
        "Run transaction, authorization and migration regressions against isolated PostgreSQL"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test,postgres-test")
    systemProperty("commerce.tests.schema", postgresTestSchema)
    filter {
        listOf(
                "OrderCreationConcurrencyTest",
                "OrderCancellationConcurrencyTest",
                "SalesStockConcurrencyTest",
                "SalesInfoEventIntegrationTest",
                "SalesStatusConcurrencyTest",
                "PaymentFlowIntegrationTest",
                "PaymentSchedulingAfterCommitTest",
                "PaymentDelayReconcilerTest",
                "PaymentRecoveryStorePostgresTest",
                "PaymentRecoveryPostgresTest",
                "DurableMockGatewayPostgresTest",
                "OrderExpirationSchedulerTest",
                "CartOrderIntegrationTest",
                "MemberCommerceApiTest",
                "MemberCommerceMigrationPostgresTest",
                "PaymentGroupIntegrationTest",
                "CouponManagementPostgresTest",
                "CouponClaimPostgresTest",
                "CouponPreviewPostgresTest",
                "CouponReservationPostgresTest",
                "RefundSchemaPostgresTest",
                "RefundIntakeServicePostgresTest",
                "DurableMockRefundGatewayPostgresTest",
                "RefundExecutionPostgresTest",
                "RefundExecutionConcurrencyPostgresTest",
                "RefundApiPostgresTest",
                "RefundRecoveryStorePostgresTest",
                "RefundRecoveryExecutionPostgresTest",
                "RefundReconcilerPostgresTest",
            )
            .forEach { includeTestsMatching("*.$it") }
    }
    doFirst {
        require(!System.getenv("COMMERCE_TEST_POSTGRES_URL").isNullOrBlank()) {
            "COMMERCE_TEST_POSTGRES_URL is required; PostgreSQL verification cannot be skipped"
        }
    }
    finalizedBy(cleanupPostgresCommerceTest)
    shouldRunAfter(tasks.named("test"))
}

tasks.named<Jar>("jar") { enabled = false }
