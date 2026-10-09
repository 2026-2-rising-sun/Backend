import java.util.UUID

plugins {
    id("shoppinglive.spring-boot-service-conventions")
    id("shoppinglive.module-boundary-conventions")
}

dependencies {
    implementation(project(":libs:common-local"))
    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-persistence"))
    implementation(project(":libs:common-resilience"))
    implementation(project(":libs:common-kafka"))
    implementation(project(":contracts:events"))

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(project(":libs:common-security"))
    testImplementation(testFixtures(project(":libs:common-security")))

    testRuntimeOnly(libs.h2)
}

// A fresh schema per invocation isolates destructive test fixtures from any existing application data.
val postgresTestSchema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "")
val cleanupPostgresCommerceTest = tasks.register<JavaExec>("cleanupPostgresCommerceTest") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.shoppinglive.commerce.support.PostgresSchemaCleanup")
    systemProperty("commerce.tests.schema", postgresTestSchema)
}

tasks.register<Test>("postgresCommerceTest") {
    group = "verification"
    description = "Run transaction, authorization and migration regressions against isolated PostgreSQL"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("spring.profiles.active", "test,postgres-test")
    systemProperty("commerce.tests.schema", postgresTestSchema)
    filter {
        listOf("OrderCreationConcurrencyTest", "OrderCancellationConcurrencyTest", "SalesStockConcurrencyTest",
            "SalesStatusConcurrencyTest", "PaymentFlowIntegrationTest", "PaymentSchedulingAfterCommitTest",
            "PaymentDelayReconcilerTest", "OrderExpirationSchedulerTest", "CartOrderIntegrationTest",
            "MemberCommerceApiTest", "MemberCommerceMigrationPostgresTest", "PaymentGroupIntegrationTest", "CouponManagementPostgresTest",
            "CouponClaimPostgresTest", "CouponPreviewPostgresTest", "CouponReservationPostgresTest",
            "RefundSchemaPostgresTest")
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
