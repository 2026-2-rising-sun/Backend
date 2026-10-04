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

tasks.register<JavaExec>("generateLocalAuthFixtures") {
    group = "verification"
    description = "Generate throwaway local RSA keys and real signed USER/SELLER tokens (never production credentials)"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.shoppinglive.common.security.test.LocalAuthFixtures")
    args(providers.gradleProperty("authFixtureDir").orElse(layout.buildDirectory.dir("local-auth").map {
        it.asFile.absolutePath
    }).get())
}
