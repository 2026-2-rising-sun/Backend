plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api(project(":libs:common-core"))
    api(libs.spring.boot.starter.data.jpa)
    runtimeOnly(libs.postgresql)
}
