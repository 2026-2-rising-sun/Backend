plugins {
    id("shoppinglive.spring-boot-service-conventions")
    id("shoppinglive.module-boundary-conventions")
}

dependencies {
    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-persistence"))
    implementation(project(":libs:common-resilience"))
    implementation(project(":contracts:events"))

    // 버전은 spring-boot-dependencies BOM 이 관리한다. 공용 버전 카탈로그는 다른 서비스와 공유하므로
    // shopping 작업 범위에서 건드리지 않고 이 서비스 안에서만 선언한다.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly(libs.postgresql)

    testRuntimeOnly(libs.h2)
}
