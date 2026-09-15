import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("shoppinglive.java-conventions")
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val bom = libs.findLibrary("spring-boot-dependencies").get()

dependencies {
    "api"(platform(bom))
    "testImplementation"(platform(bom))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
