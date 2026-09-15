plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api(project(":contracts:events"))
    api(libs.spring.kafka)
    api(libs.spring.boot.autoconfigure)
    implementation(libs.jackson.databind)
}
