# 도구 실행 참고

명령이나 실행 조건이 필요할 때 해당 절만 참고한다. 아래 명령을 매 작업마다 전부 실행할 필요는 없다. 검증 범위는 변경 영향과 [테스트](rules/tests.md)·[계약](rules/contracts.md) 기준에 따라 선택한다.

현재 저장소는 Gradle Wrapper와 Java 21을 사용한다. 설정 원본은 [build.gradle.kts](../build.gradle.kts), [.editorconfig](../.editorconfig), 각 서비스의 테스트 설정이다. 안내와 다르면 실제 설정을 확인하고 관련 설명을 갱신한다.

## 포맷과 빌드

- EditorConfig와 Spotless 설정을 따른다. Java는 Google Java Format의 AOSP 스타일과 미사용 import 정리, Gradle Kotlin은 ktfmt를 사용한다. 세부 값과 버전은 설정 파일에서 확인한다.
- 별도 Java 린트(Checkstyle·PMD·SpotBugs)는 현재 구성되어 있지 않다. Spotless 통과를 버그 탐지나 설계 품질 검증으로 설명하지 않는다.

아래는 Member에서 선택해 사용할 수 있는 명령이다. 대상 서비스에 맞춰 모듈 경로를 바꾸고, 공통 모듈은 `:common-module`을 사용한다.

```bash
java -version
./gradlew :services:member-service:spotlessCheck
./gradlew :services:member-service:spotlessApply
./gradlew :services:member-service:test
./gradlew :services:member-service:integrationTest
./gradlew :services:member-service:build
git diff --check
```

`spotlessApply`는 파일을 수정한다. 작업 대상에만 적용하고 diff에 무관한 수정이 섞였는지 확인한다. `build`에는 `check`와 일반 통합 검사가 연결되어 있다.

## 현재 테스트 선택 방식

| 작업 | 대상 | 주의점 |
|---|---|---|
| `test` | `src/test` | 실제 실행·캐시 재사용 구분 |
| `integrationTest` | `src/integrationTest` 중 `postgres`, `redis` 제외 | 조건부 테스트는 스킵될 수 있음 |
| `migrationTest` | 통합 테스트 중 `postgres & migration` | 서비스 모듈만 등록, 환경 준비 필요 |
| `redisTest` | 통합 테스트 중 `redis` | 서비스 모듈만 등록, 환경 준비 필요 |

`postgres` 단독 테스트를 실행하는 전용 작업은 현재 없다. `--tests` 필터는 위 태그 조건에 추가로 적용되므로 제외 조건을 우회하지 못한다. 외부 전용 작업은 환경 조건과 컴파일된 테스트 존재를 확인하며, 실행된 테스트가 0건이거나 스킵이 있으면 실패하도록 설정되어 있다. Gradle의 `NO-SOURCE` 등 작업 상태와 생성된 결과도 확인한다. 태그만으로 DB 연결이나 컨테이너가 준비되지 않는다.

## PostgreSQL·Redis 환경

외부 테스트는 데이터를 삭제·초기화할 수 있으므로 해당 작업 전용의 격리된 DB·Redis를 준비한다. 실제 연결 설정과 테스트의 실행 조건을 읽고 사용한다.

| 서비스 | PostgreSQL 선택과 연결 설정 |
|---|---|
| Member | `MEMBER_TEST_DB_URL`을 PostgreSQL JDBC URL로 설정하고 `MEMBER_TEST_DB_DRIVER=org.postgresql.Driver`, `MEMBER_TEST_DB_USER`, `MEMBER_TEST_DB_PASSWORD` 지정 |
| Shopping·Commerce | 각각 `SHOPPING_TEST_POSTGRES_`, `COMMERCE_TEST_POSTGRES_` 접두사의 `URL`, `USER`, `PASSWORD` 사용 |
| Notification | `NOTIFICATION_TEST_POSTGRES_URL`, `NOTIFICATION_TEST_DB_DRIVER=org.postgresql.Driver`, `NOTIFICATION_TEST_DB_USER`, `NOTIFICATION_TEST_DB_PASSWORD` 사용 |
| Live | `LIVE_PG_TEST=1`, `LIVE_PG_PORT`와 [postgres 프로필](../services/live-service/src/integrationTest/resources/application-postgres.yml)의 DB·계정 설정 확인 |

환경을 준비한 뒤 대상 서비스의 작업을 실행한다. 아래 명령만으로 환경이 생성되지는 않는다.

```bash
./gradlew :services:member-service:migrationTest
LIVE_PG_TEST=1 ./gradlew :services:live-service:migrationTest
LIVE_REDIS_TEST=1 ./gradlew :services:live-service:redisTest
```

현재 Redis 태그 테스트는 Live에 있다. 실제 연결은 localhost와 `LIVE_REDIS_PORT`(기본 56379)를 사용한다. 다른 서비스에도 `redisTest` 작업은 등록되지만 대상 테스트가 있다는 뜻은 아니다.

Member·Notification의 일반 통합 테스트도 환경변수로 DB가 바뀔 수 있다. H2 실행으로 보고하기 전에 해당 서비스의 테스트 DB 환경변수가 주입돼 있는지 확인한다. 서비스별 추가 설정은 테스트 코드와 `src/integrationTest/resources`를 원본으로 확인한다.

## Kafka와 이벤트 표본

Commerce의 `SalesInfoEventKafkaTest`, `CouponSyncKafkaTest`는 `COMMERCE_TEST_KAFKA_SERVERS`가 비어 있으면 스킵된다. 격리된 브로커를 준비한 후 해당 환경변수를 설정하고 일반 `integrationTest`에서 선택해 실행한다. 환경변수만 바꾼 재실행은 `--rerun-tasks`로 이전 결과 재사용을 피한다.

```bash
./gradlew :services:commerce-service:integrationTest --tests '*SalesInfoEventKafkaTest' --tests '*CouponSyncKafkaTest' --rerun-tasks
```

Shopping의 `SalesInfoEventKafkaTest`는 EmbeddedKafka를 사용하고 일반 `integrationTest`에 포함된다. 전용 `kafka` 태그나 `kafkaTest` 작업은 없다. 브로커 기반 검증과 동일 JVM의 EmbeddedKafka 검증을 구분한다.

## API·이벤트 계약 검사

아래는 Python 가상환경에서 실행하는 예시다. 환경 생성·설치는 최초 준비 시에만 필요하며, 의존성이 설치된 기존 환경이 있으면 재사용한다.

```bash
python3 -m venv .venv-api
.venv-api/bin/python -m pip install -r .github/scripts/api/requirements.txt
.venv-api/bin/python .github/scripts/api/check_structure.py
.venv-api/bin/python .github/scripts/api/check_events.py
```

API 정적 검사는 직접 선언한 Spring MVC 경로·메서드를 비교한다. 이벤트 기본 검사는 YAML 참조·스키마·예시를 확인한다. 보장 범위와 구현 대조 절차는 [계약 규칙](rules/contracts.md)을 따른다.

이벤트 직렬화 표본까지 확인하려면 현재 코드로 표본을 먼저 생성한다.

```bash
./gradlew :services:commerce-service:test --tests '*EventContractTest' :services:live-service:test --tests '*EventContractTest' --rerun-tasks
.venv-api/bin/python .github/scripts/api/check_events.py --samples
```

표본은 서비스별 `build/contract-samples`에 생성된다. 오래된 표본만 검사한 결과를 현재 구현의 검증으로 보고하지 않는다. 직렬화 표본 통과도 실제 생산자→브로커→소비자 전달·DB 반영 증거와 구분한다.

## 검증 범위 판단

문서 변경은 링크와 내용 정합성, 코드 변경은 영향을 받는 모듈과 동작을 중심으로 확인한다. 공통 코드·계약·외부 저장소 변경은 실제 소비자와 실행 환경에 따라 범위를 넓힌다. 필수 계약 검사는 [계약 규칙](rules/contracts.md)을 따른다.

기존 [CI 선택 로직](../.github/scripts/ci/classify_changes.py)의 결과를 전체 테스트 성공으로 확대하지 않는다. 이미 통과한 검사는 변경·실패·미해결 우려가 없으면 반복하지 않는다.
