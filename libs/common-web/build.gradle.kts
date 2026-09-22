plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api(project(":libs:common-core"))
    api(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.spring.orm)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.validation)
}
