plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api(libs.resilience4j.spring.boot3)
    api(libs.spring.boot.starter.aop)
    implementation(libs.spring.boot.autoconfigure)
}
