import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("shoppinglive.java-conventions")
    id("org.springframework.boot")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val bom = libs.findLibrary("spring-boot-dependencies").get()

dependencies {
    "implementation"(platform(bom))
    "testImplementation"(platform(bom))

    "implementation"(libs.findLibrary("spring-boot-starter-web").get())
    "implementation"(libs.findLibrary("spring-boot-starter-actuator").get())
    "implementation"(libs.findLibrary("spring-boot-starter-validation").get())

    "testImplementation"(libs.findLibrary("spring-boot-starter-test").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") {
    enabled = false
}
