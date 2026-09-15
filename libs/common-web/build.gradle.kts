plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api(project(":libs:common-core"))
    api(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.starter.test)
}
