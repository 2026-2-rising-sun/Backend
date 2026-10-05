plugins {
    id("shoppinglive.spring-boot-service-conventions")
    id("shoppinglive.module-boundary-conventions")
}

dependencies {
    implementation(project(":libs:common-local"))
    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-security"))
    implementation(project(":libs:common-persistence"))
    implementation(project(":libs:common-resilience"))
    implementation(project(":libs:common-kafka"))
    implementation(project(":contracts:events"))

    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    implementation("software.amazon.awssdk:ivs:2.49.6")

    testRuntimeOnly(libs.h2)
    testImplementation(testFixtures(project(":libs:common-security")))
}
