# ShoppingLive Backend

JDK 21 / Gradle 기반 라이브 커머스 백엔드입니다.

| 서비스 | 담당 기능 | 로컬 포트 |
|---|---|---|
| [Member](services/member-service) | 회원·인증·세션 | 8081 |
| [Shopping](services/shopping-service) | 상품·이미지 | 8082 |
| [Commerce](services/commerce-service) | 판매·재고·쿠폰·장바구니·주문·결제·환불 | 8083 |
| [Live](services/live-service) | 방송·채팅·좋아요·SSE | 8084 |
| [Notification](services/notification-service) | 알림 서비스 시작 골격 | 8085 |

서비스는 업무 도메인 안에 필요한 controller/service/repository/entity/dto 계층을 둡니다. 공통 웹·인증·JPA 코드와 인증 테스트 도구는 [common-module](common-module)에서 공유합니다. 서비스 사이의 Java 모듈 의존은 현재 사용하지 않으며, 이를 강제하는 별도 빌드 검사는 없습니다.

## 빌드와 테스트

```sh
./gradlew build
./gradlew test
./gradlew integrationTest
./gradlew spotlessApply
```

`build`는 단위·통합 테스트와 Java·KTS 포맷 검사를 포함합니다. 단위 테스트는 `src/test`, 통합 테스트는 `src/integrationTest`에 있습니다. 외부 PostgreSQL·Redis가 필요한 테스트는 환경을 지정하지 않으면 건너뛸 수 있습니다. 기본 빌드 성공을 실제 DB·전체 서비스 연동 성공으로 해석하지 않습니다.

[수동 DB workflow](.github/workflows/postgres.yml)는 테스트용 PostgreSQL·Redis를 준비하고, 대상 테스트가 실행됐는지와 실패·건너뜀이 없는지를 검사합니다. 이 workflow의 결과 확인 도구만 `.github/scripts`에 유지합니다. Prism과 외부 서비스 E2E·복구 검증 스크립트는 제거했으며, 그 검증 범위가 서비스 내부 테스트로 모두 대체된 것은 아닙니다.

## 로컬 실행과 API 문서

```sh
node scripts/local/auth-env.cjs create --access-ttl PT15M --refresh-ttl P30D --out build/local/ide-auth
./gradlew :services:commerce-service:bootRun --args='--spring.profiles.active=local'
```

Notification 인증을 연결하려면 생성 파일에 `NOTIFICATION_MEMBER_SERVICE_TOKEN`이 있어야 합니다. 이전 생성 파일을 사용하는 경우 새 디렉터리에 인증 정보를 생성하고 모든 서비스가 같은 새 파일을 사용하도록 실행 설정을 갱신합니다. 기존 인증 파일은 생성 도구가 덮어쓰지 않습니다.

각 서비스에 필요한 DB·Redis와 외부 서비스 주소를 준비한 뒤 실행합니다. 인증 정보 생성 명령만으로 전체 실행 환경이 준비되는 것은 아닙니다. 기본 Application 실행은 프로필을 지정하지 않으면 local을 선택하며, 명시적으로 지정한 설정이 로컬 생성 파일보다 우선합니다.

Member·Shopping·Commerce·Live의 local 프로필은 `/swagger-ui.html`과 `/v3/api-docs`를 제공합니다. 다른 환경에서는 `API_DOCS_ENABLED=true`로 활성화합니다. 현재 API 문서는 컨트롤러·DTO에서 Springdoc이 생성합니다.

OpenAPI YAML 원본은 `contracts/api`에서만 관리합니다. CI는 서버를 실행하지 않고 Java 컨트롤러의 경로·HTTP 메서드를 문서와 양방향 비교합니다. 실제 응답·DTO 스키마·인증 동작은 이 검사 범위에 포함되지 않습니다. API 계약을 바꿀 때 문서도 함께 갱신하는 절차는 [AGENTS.md](AGENTS.md)에 있습니다. 기존 Flyway SQL은 적용 이력과 체크섬을 보존하기 위해 수정하지 않습니다. 옛 이관 문서 경로가 주석에 남아 있어도 현재 실행 안내로 사용하지 않습니다.

팀 문서: [ShoppingLive 팀 메인](https://app.notion.com/p/3e9226545d1581989a9edefd9da775c4)

## API 정적 구조 검사

```sh
python3 -m venv .venv-api
.venv-api/bin/python -m pip install -r .github/scripts/api/requirements.txt
.venv-api/bin/python .github/scripts/api/check_structure.py
```

직접 선언한 Spring MVC 매핑을 읽습니다. 마지막 `/`, 조건부·개발용 매핑도 포함하며, 지원하지 않는 상속·매핑 표현식은 성공으로 넘기지 않고 실패합니다. 테스트는 `python -m unittest discover -s .github/scripts/api`로 실행합니다.

이벤트 계약 원본은 `contracts/events`의 AsyncAPI YAML입니다. `.github/scripts/api/check_events.py`는 참조·스키마·예시를 검사하고, `--samples`로 서비스 테스트가 생성한 실제 직렬화 표본을 검사합니다. 이는 실제 Kafka 전달이나 소비자 DB 반영을 검증하지 않습니다.

### 쿠폰 표시정보 생산자 검증

Commerce는 쿠폰 생성·설정 변경·발급 수량 소진을 `commerce.coupon-info.v1`에 전체 스냅샷으로 전달한다. 계약 원본은 [`contracts/events/commerce-coupon-info.yaml`](contracts/events/commerce-coupon-info.yaml)이며, 초기 적재·주기적 복구 API는 [`contracts/api/commerce-service.yaml`](contracts/api/commerce-service.yaml)의 `/v1/internal/coupon-snapshots` 목록·단건 경로다. Shopping 서비스 토큰만 허용한다. 일반 상품 조회는 Shopping의 이벤트 사본을 사용하도록 구현할 예정이며 수신 작업은 [#229](https://github.com/2026-2-rising-sun/Backend/issues/229)에서 관리한다.

쿠폰별 `snapshotVersion`은 이벤트와 API에 동일하게 적용한다. 목록은 미래·진행 쿠폰과 소진 쿠폰을 포함하며 페이지 전체의 단일 시점 일관성을 보장하지 않는다. 소비를 먼저 시작하고 전체 페이지를 주기적으로 순회한다. 이미 저장한 쿠폰은 단건 조회로 종료·기간 변경·대상 변경 누락도 복구해야 한다. 발급 시작 포함·종료 제외의 시간 경계는 소비자가 현재 시각으로 판단한다.

기본 `:services:commerce-service:build`는 H2에서 업무·API·Outbox 원자성을 검사한다. 실제 PostgreSQL은 다음 환경으로 실행하며, 실행마다 격리 스키마를 만들고 종료 시 제거한다.

```bash
COMMERCE_TEST_POSTGRES_URL=jdbc:postgresql://localhost:55432/commerce_test \
COMMERCE_TEST_POSTGRES_USER=postgres COMMERCE_TEST_POSTGRES_PASSWORD=test \
./gradlew :services:commerce-service:postgresCommerceTest --tests '*CouponSyncIntegrationTest'
```

실제 Kafka를 함께 확인하려면 위 PostgreSQL 환경에 `COMMERCE_TEST_KAFKA_SERVERS=localhost:29092`를 더하고 `--tests '*CouponSyncKafkaTest'`를 실행한다. 이 테스트는 실제 Outbox 행을 production 릴레이·template으로 전송하고 판매 이벤트 template과 직렬화가 섞이지 않는지 확인한다. 별도 서비스 전체 E2E를 보장하지 않는다. 브로커 없이 돌리는 테스트에서는 `shoppinglive.outbox.relay.enabled=false`로 주기적 발행을 끈다.
