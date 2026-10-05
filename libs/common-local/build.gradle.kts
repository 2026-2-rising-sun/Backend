plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api("org.springframework.boot:spring-boot")
    implementation(libs.jackson.databind)
    testImplementation(libs.spring.boot.starter.test)
}
