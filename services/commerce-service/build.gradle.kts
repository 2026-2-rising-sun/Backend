plugins {
    id("shoppinglive.spring-boot-service-conventions")
    id("shoppinglive.module-boundary-conventions")
}

dependencies {
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
