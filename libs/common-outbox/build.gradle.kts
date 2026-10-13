// 서비스 DB 트랜잭션과 같은 커밋으로 이벤트를 기록하고, 별도 릴레이가 Kafka 로 보낸다.
// 이벤트 종류를 모르는 범용 모듈이라 새 이벤트를 추가해도 여기는 바뀌지 않는다.
plugins {
    id("shoppinglive.library-conventions")
}

dependencies {
    api(project(":contracts:events"))
    api(project(":libs:common-persistence"))
    api(libs.spring.kafka)
    api(libs.spring.boot.autoconfigure)
    implementation(libs.jackson.databind)

    testImplementation(libs.mockito.core)
    testImplementation(libs.jackson.datatype.jsr310)
}
