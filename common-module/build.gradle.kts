plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testFixturesApi(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    compileOnly("io.swagger.core.v3:swagger-models-jakarta:2.2.55")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
    testFixturesApi("org.springframework.security:spring-security-oauth2-jose")
    testFixturesImplementation(
        "org.springframework.boot:spring-boot-starter-oauth2-resource-server"
    )
}
