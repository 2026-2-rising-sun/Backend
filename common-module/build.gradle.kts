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
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-aop")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    compileOnly("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.security:spring-security-test")
    compileOnly("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.kafka:spring-kafka")
    testRuntimeOnly("com.h2database:h2")
    testFixturesApi("org.springframework.security:spring-security-oauth2-jose")
    testFixturesImplementation(
        "org.springframework.boot:spring-boot-starter-oauth2-resource-server"
    )
}
