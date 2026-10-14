# syntax=docker/dockerfile:1
# build context는 레포 루트다: docker build -f services/commerce-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon --version

COPY settings.gradle.kts build.gradle.kts ./
COPY common-module common-module
# settings.gradle.kts가 include하는 프로젝트는 디렉터리가 실재해야 하므로 5개 서비스의 빌드 스크립트를 모두 가져온다.
COPY services/member-service/build.gradle.kts services/member-service/
COPY services/shopping-service/build.gradle.kts services/shopping-service/
COPY services/commerce-service/build.gradle.kts services/commerce-service/
COPY services/live-service/build.gradle.kts services/live-service/
COPY services/notification-service/build.gradle.kts services/notification-service/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :services:commerce-service:dependencies --configuration runtimeClasspath

# commerce-service가 사용하는 모듈 소스만 복사한다.
COPY services/commerce-service services/commerce-service
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :services:commerce-service:bootJar

FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring

WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/services/commerce-service/build/libs/*.jar app.jar

USER spring

# 컨테이너 포트는 5개 서비스 모두 8080으로 통일한다 (application.yml의 서비스별 포트는 로컬 동시 기동용).
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
