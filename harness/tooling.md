# 도구 실행 참고

명령이나 실행 조건이 필요할 때 해당 절만 참고한다. 아래 명령을 매 작업마다 전부 실행할 필요는 없다. 검증 범위는 변경 영향과 [테스트](quality/tests.md)·[계약](workflow/contracts.md) 기준에 따라 선택한다.

현재 저장소는 Gradle Wrapper와 Java 21을 사용한다. 설정 원본은 [build.gradle.kts](../build.gradle.kts), [.editorconfig](../.editorconfig), 각 서비스의 테스트 설정이다. 안내와 다르면 실제 설정을 확인하고 관련 설명을 갱신한다.

## 로컬 검증 실행기

[verify.py](verify.py)는 변경 내용으로 기존 CI 검사 선택기를 호출한다. 실행 전 [API 검사 의존성](#api이벤트-계약-검사)이 설치된 Python 환경을 사용한다. Python은 3.10 이상, Gradle 검사는 Java 21과 기존 도구가 필요하다.

```bash
# 검사 목록·선택 이유만 출력. 파일이나 Git 상태를 수정하지 않는다.
.venv-api/bin/python harness/verify.py --dry-run

# HEAD 이후 staged·unstaged·새 파일을 포함한 현재 작업 트리 검사
.venv-api/bin/python harness/verify.py

# PR 단위: 기준 ref와 HEAD의 merge-base부터 현재 작업 트리까지 검사
.venv-api/bin/python harness/verify.py --base origin/dev

# 격리 DB·Redis를 직접 준비하고 연결 설정을 확인한 경우만 외부 검사 실행
.venv-api/bin/python harness/verify.py --external
```

- staged와 작업 트리가 다르면 둘 다 검사 선택에 반영하지만 실제 실행 대상은 작업 트리다. 이 결과로 서로 다른 staged 내용까지 검증했다고 보고하지 않는다.
- 기준 ref는 자동 fetch하지 않는다. 원격 최신성은 [동기화 절차](workflow/README.md#시작과-동기화)에서 확인한다. 잘못된 ref나 충돌은 준비 실패다.
- 기본은 선택된 일반 검사만 실행한다. 선택된 외부 검사가 있으면 `--external`과 서비스별 환경 조건이 갖춰지기 전까지 미실행으로 남긴다. 테스트 대상 서비스에 DB URL 환경변수가 주입돼 있으면 일반 빌드·테스트도 `--external`이 필요하다. 테스트를 실행하지 않는 `spotlessCheck`는 이 제한을 받지 않는다. 인프라나 자격증명을 자동 생성하지 않는다.
- 결과와 명령별 로그는 무시되는 `build/harness/`에 저장하고 최신 결과 파일을 덮어쓴다. 종료 코드는 `0` 정상(변경 없음·자동 검사 대상 없음 포함), `1` 실패, `2` 미완료(미실행·스킵·0건·검사 중 diff 변경)다. 검사 대상 없음은 테스트 통과가 아니다.
- Gradle XML에서 실패·스킵·건수와 작업의 `UP-TO-DATE`·`FROM-CACHE`·`NO-SOURCE`를 구분한다. 빌드는 대상 모듈의 `test`·`integrationTest` 작업 결과를 모두 확인하며, 작업 출력이나 필요한 XML이 빠지면 종료 코드가 0이어도 미완료다. 조건부 Kafka 스킵도 미완료로 표시한다. 원래 테스트가 없는 단위 작업의 NO-SOURCE는 그대로 보고한다.
- 현재 diff와 실행 결과를 묶어 기록하며 검사 중 소스가 바뀌면 미완료로 표시한다. 이후 소스 변경의 유효성을 보장하는 인증서가 아니므로 최종 diff가 바뀌면 필요한 검사를 다시 선택한다.
- API 자동 선택은 경로·HTTP 메서드 중심이다. DTO·응답·인증·멱등성 의미는 직접 계약과 대조하고 필요한 기존 검사를 추가 실행한다. 선택기가 postgres 단독 테스트의 실행 공백을 해결하지는 않는다.
- 포맷 자동 수정·코드 수정·Git 작업은 실행기 기능이 아니다. 실패 로그를 읽고 에이전트가 범위 안에서 수정·재검증한다.

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
| `postgresTest` | Commerce 통합 테스트 중 `postgres & !migration` | 격리 PostgreSQL 필요, 다른 서비스에는 등록하지 않음 |
| `migrationTest` | 통합 테스트 중 `postgres & migration` | 서비스 모듈만 등록, 환경 준비 필요 |
| `redisTest` | 통합 테스트 중 `redis` | 서비스 모듈만 등록, 환경 준비 필요 |

Commerce의 `postgresTest`가 `postgres` 단독 업무 테스트를 실행한다. 다른 서비스에는 전용 작업이 없다. `--tests` 필터는 위 태그 조건에 추가로 적용되므로 제외 조건을 우회하지 못한다. 외부 전용 작업은 환경 조건과 컴파일된 테스트 존재를 확인하며, 실행된 테스트가 0건이거나 스킵이 있으면 실패하도록 설정되어 있다. Gradle의 `NO-SOURCE` 등 작업 상태와 생성된 결과도 확인한다. 태그만으로 DB 연결이나 컨테이너가 준비되지 않는다.

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
./gradlew :services:commerce-service:postgresTest --tests '*NotificationEventPostgresTest'
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

Commerce의 `SalesInfoMainConfigurationKafkaTest`는 main YAML을 직접 읽어 EmbeddedKafka 전송과 중복 설정 오류 재현을 검사하며 기본 `integrationTest`에서 항상 실행된다. Shopping의 `SalesInfoEventKafkaTest`도 EmbeddedKafka를 사용하고 일반 `integrationTest`에 포함된다. 전용 `kafka` 태그나 `kafkaTest` 작업은 없다. 브로커 기반 검증과 동일 JVM의 EmbeddedKafka 검증을 구분한다.

## API·이벤트 계약 검사

아래는 Python 가상환경에서 실행하는 예시다. 환경 생성·설치는 최초 준비 시에만 필요하며, 의존성이 설치된 기존 환경이 있으면 재사용한다.

```bash
python3 -m venv .venv-api
.venv-api/bin/python -m pip install -r .github/scripts/api/requirements.txt
.venv-api/bin/python .github/scripts/api/check_structure.py
.venv-api/bin/python .github/scripts/api/check_events.py
```

API 정적 검사는 직접 선언한 Spring MVC 경로·메서드를 비교한다. 이벤트 기본 검사는 YAML 참조·스키마·예시를 확인한다. 보장 범위와 구현 대조 절차는 [계약 규칙](workflow/contracts.md)을 따른다.

이벤트 직렬화 표본까지 확인하려면 현재 코드로 표본을 먼저 생성한다.

```bash
./gradlew :services:commerce-service:test --tests '*EventContractTest' :services:live-service:test --tests '*EventContractTest' --rerun-tasks
.venv-api/bin/python .github/scripts/api/check_events.py --samples
```

표본은 서비스별 `build/contract-samples`에 생성된다. 실행기는 앞선 빌드가 실패·미완료·미실행이면 표본 검사도 실행하지 않는다. 오래된 표본만 검사한 결과를 현재 구현의 검증으로 보고하지 않는다. 직렬화 표본 통과도 실제 생산자→브로커→소비자 전달·DB 반영 증거와 구분한다.

## 검증 범위 판단

문서 변경은 링크와 내용 정합성, 코드 변경은 영향을 받는 모듈과 동작을 중심으로 확인한다. 공통 코드·계약·외부 저장소 변경은 실제 소비자와 실행 환경에 따라 범위를 넓힌다. 필수 계약 검사는 [계약 규칙](workflow/contracts.md)을 따른다.

기존 [CI 선택 로직](../.github/scripts/ci/classify_changes.py)의 결과를 전체 테스트 성공으로 확대하지 않는다. 이미 통과한 검사는 변경·실패·미해결 우려가 없으면 반복하지 않는다.
