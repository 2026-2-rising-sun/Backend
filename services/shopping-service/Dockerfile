# syntax=docker/dockerfile:1
# build context는 레포 루트다: docker build -f services/shopping-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon --version

COPY settings.gradle.kts build.gradle.kts ./
COPY build-logic build-logic
COPY contracts/events/build.gradle.kts contracts/events/
COPY libs/common-core/build.gradle.kts libs/common-core/
COPY libs/common-web/build.gradle.kts libs/common-web/
COPY libs/common-security/build.gradle.kts libs/common-security/
COPY libs/common-persistence/build.gradle.kts libs/common-persistence/
COPY libs/common-kafka/build.gradle.kts libs/common-kafka/
COPY libs/common-resilience/build.gradle.kts libs/common-resilience/
# settings.gradle.kts가 include하는 프로젝트는 디렉터리가 실재해야 하므로 5개 서비스의 빌드 스크립트를 모두 가져온다.
COPY services/member-service/build.gradle.kts services/member-service/
COPY services/shopping-service/build.gradle.kts services/shopping-service/
COPY services/commerce-service/build.gradle.kts services/commerce-service/
COPY services/live-service/build.gradle.kts services/live-service/
COPY services/notification-service/build.gradle.kts services/notification-service/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :services:shopping-service:dependencies --configuration runtimeClasspath

# shopping-service가 실제로 의존하는 모듈 소스만 복사한다(common-kafka, common-security 등
# 의존하지 않는 모듈은 build.gradle.kts만 있으면 되고 전체 소스가 필요 없다). 이렇게 하면
# 이 서비스가 안 쓰는 모듈이 바뀌었을 때 이 레이어의 캐시가 불필요하게 깨지지 않는다.
COPY contracts/events contracts/events
COPY libs/common-core libs/common-core
COPY libs/common-web libs/common-web
COPY libs/common-persistence libs/common-persistence
COPY libs/common-resilience libs/common-resilience
COPY services/shopping-service services/shopping-service
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :services:shopping-service:bootJar

FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring

WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/services/shopping-service/build/libs/*.jar app.jar

USER spring

# 컨테이너 포트는 5개 서비스 모두 8080으로 통일한다 (application.yml의 서비스별 포트는 로컬 동시 기동용).
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
