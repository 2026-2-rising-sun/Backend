plugins {
    id("shoppinglive.spring-boot-service-conventions")
    id("shoppinglive.module-boundary-conventions")
}

dependencies {
    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-resilience"))
    implementation(project(":contracts:events"))
}
