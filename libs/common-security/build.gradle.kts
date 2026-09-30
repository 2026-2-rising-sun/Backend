plugins {
    id("shoppinglive.library-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":libs:common-core"))
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(project(":libs:common-web"))
    implementation(libs.jackson.databind)
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testFixturesImplementation(libs.jackson.databind)
}
