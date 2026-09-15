# build context는 레포 루트다: docker build -f services/shopping-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version

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
RUN ./gradlew --no-daemon :services:shopping-service:dependencies --configuration runtimeClasspath

COPY contracts contracts
COPY libs libs
COPY services/shopping-service services/shopping-service
RUN ./gradlew --no-daemon :services:shopping-service:bootJar

FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring

WORKDIR /app
COPY --from=build --chown=spring:spring /workspace/services/shopping-service/build/libs/*.jar app.jar

USER spring

# 컨테이너 포트는 5개 서비스 모두 8080으로 통일한다 (application.yml의 서비스별 포트는 로컬 동시 기동용).
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
