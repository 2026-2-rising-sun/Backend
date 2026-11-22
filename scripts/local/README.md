# 서비스를 로컬에서 연결하기

로컬 미들웨어는 [Infra `local/docker-compose.yml`](https://github.com/2026-2-rising-sun/Infra/blob/main/local/README.md) 하나로 실행한다. Backend는 앱과 인증 도구만 제공한다. 서비스별 DB는 분리하고 PostgreSQL·Redis·Kafka 실행 설정과 DB 초기화는 Infra에서 관리한다. [환경 구조와 전환 조건](../../docs/design-docs/flows/local-environment.md)을 먼저 확인한다.

Infra 저장소 루트에서 최초 한 번 `.env`를 만들고 비밀번호를 채운 뒤 실행한다. 기존 `.env`는 덮어쓰지 않는다.

```sh
cp local/.env.example local/.env
# local/.env의 POSTGRES_PASSWORD를 설정한다.
docker compose --env-file local/.env -f local/docker-compose.yml up -d --wait
```

기존 볼륨에 서비스·테스트 DB가 없다면 Infra README의 서비스별 DB 생성 절차를 따른다. DB를 추가하려고 볼륨을 지우지 않는다. 토픽 선언과 가짜 토스는 기본 스택에 포함되며 CDC Connect는 별도 `cdc` 프로필과 등록 절차가 필요하다.

이후 명령은 Backend 저장소 루트에서 실행한다. 앱 포트는 Member8081, Catalog8082, CommerceCore8083, Live8084, Notification8085, Payment8086이다.

```sh
node scripts/local/auth-env.cjs create --access-ttl PT4H --refresh-ttl P1D --out build/local/msa-auth
./gradlew :services:member-service:bootJar :services:catalog-service:bootJar :services:commercecore-service:bootJar :services:payment-service:bootJar :services:live-service:bootJar :services:notification-service:bootJar
```

Java 21을 사용한다. 생성된 `build/local/msa-auth/env.json`은 로컬 키·호출자별 토큰을 포함하며 Git에서 제외된다. `auth-env.cjs exec`는 실행할 서비스에 필요한 인증정보만 전달한다. 아래 명령은 서비스를 띄울 터미널마다 적용하고, 서비스 이름과 jar 경로를 각각 바꿔 실행한다. 포트·계정·비밀번호는 Infra `local/.env` 값으로 맞춘다. Payment `.env`를 사용 중이면 `PAYMENT_DB_*`·`PAYMENT_KAFKA_SERVERS`도 확인한다.

```sh
export POSTGRES_PORT=5432 POSTGRES_USER=shoppinglive
export POSTGRES_PASSWORD='Infra local/.env에 설정한 비밀번호'
export KAFKA_PORT=29092 REDIS_PORT=6379
node scripts/local/auth-env.cjs exec member --env build/local/msa-auth/env.json -- java -jar services/member-service/build/libs/member-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
node scripts/local/auth-env.cjs exec catalog --env build/local/msa-auth/env.json -- java -jar services/catalog-service/build/libs/catalog-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
node scripts/local/auth-env.cjs exec commercecore --env build/local/msa-auth/env.json -- java -jar services/commercecore-service/build/libs/commercecore-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
node scripts/local/auth-env.cjs exec payment --env build/local/msa-auth/env.json -- java -jar services/payment-service/build/libs/payment-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
node scripts/local/auth-env.cjs exec live --env build/local/msa-auth/env.json -- java -jar services/live-service/build/libs/live-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
node scripts/local/auth-env.cjs exec notification --env build/local/msa-auth/env.json -- java -jar services/notification-service/build/libs/notification-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Member local 프로필은 `user@local.test / user`, `seller@local.test / seller` 계정을 초기화한다. `/v1/auth/login`으로 로그인하며 사용자 JWT를 다른 서비스의 공개 API에 전달한다. `/actuator/health/readiness`는 서비스별 포트에서 조회한다. 외부 `/api` prefix는 Frontend 프록시가 제거하고 앱의 `/v1` 경로로 전달한다.

| 설정 | 기본 연결 |
|---|---|
| `COMMERCECORE_PAYMENT_BASE_URL` / `COMMERCECORE_PAYMENT_SERVICE_TOKEN` | Core → Payment `http://localhost:8086`, `X-Service-Token` |
| `CATALOG_SALES_CLIENT_BASE_URL` / `CATALOG_COMMERCECORE_SERVICE_TOKEN` | Catalog 판매·쿠폰 대조 → Core `http://localhost:8083` |
| `LIVE_PRODUCTS_COMMERCECORE_URL` / `LIVE_COMMERCECORE_SERVICE_TOKEN` | Live 상품·판매 조회 → Core `http://localhost:8083` |
| `COMMERCECORE_IMAGE_DIR` / `COMMERCECORE_IMAGE_PUBLIC_BASE_URL` | Core 이미지 저장 폴더 / 공개 `/api/commercecore` |
| `CATALOG_MEMBER_SERVICE_TOKEN`, `COMMERCECORE_MEMBER_SERVICE_TOKEN` | 각 서비스 → Member 세션 확인 |

Payment 내부 API는 결제 생성·결제창·조회·승인만 연다(#348·#338). Core 주문서 결제 중계는 이 API를 쓴다. Core의 옛 결제·환불 호출(`HttpPaymentClient`)과 `/v1/dev/payment-scenarios`(결제 시나리오 지정)는 지운 API를 불러 403을 받는다. Payment local 프로필은 결제 승인을 Infra의 가짜 토스 `toss-mock`(localhost:59080)으로 보낸다. 실제 토스는 `--spring.profiles.active=local,toss-real`로 켠다. 로컬 토스 확인은 [Payment 로컬 도구](../../services/payment-service/local/README.md)를 따른다. 일반 이벤트는 Infra Kafka `localhost:29092`를 사용한다. Catalog 상품·SKU snapshot은 Core Outbox → CDC Connect → 같은 Kafka → Catalog 경로이며 기본 스택 기동만으로 CDC가 활성화되지는 않는다. 기존 판매·쿠폰 토픽은 유지한다. 브라우저의 결제·환불 요청은 Core facade를 거친다.

Infra 저장소 루트에서 회귀 전용 Redis를 띄운다. 앱 Redis6379와 다른 인스턴스다.

```sh
docker compose --env-file local/.env -f local/docker-compose.yml --profile tests up -d --wait redis-test
```

Backend 저장소 루트에서 테스트를 실행한다. 포트를 변경했다면 Infra의 `REDIS_TEST_PORT`와 맞춘다.

```sh
LIVE_REDIS_TEST=1 LIVE_REDIS_PORT=56379 ./gradlew :services:live-service:redisTest
```

서비스별 PostgreSQL 업무 회귀는 `CATALOG_TEST_POSTGRES_*`, `COMMERCECORE_TEST_POSTGRES_*`, `PAYMENT_TEST_POSTGRES_*`의 `URL`, `USER`, `PASSWORD`를 지정한 뒤 각 서비스의 `postgresTest`로 실행한다. Infra는 `catalog_test`, `commercecore_test`, `payment_test` 등 서비스별 테스트 DB와 병렬 worker용 `catalog_product_test`, `commercecore_product_test`, `commercecore_payment_test`, `payment_execution_test`를 준비한다. 테스트는 DB 내용을 초기화할 수 있으므로 업무 DB를 지정하지 않는다.

```sh
export COMMERCECORE_TEST_POSTGRES_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/commercecore_test"
export COMMERCECORE_TEST_POSTGRES_USER="$POSTGRES_USER"
export COMMERCECORE_TEST_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"
./gradlew :services:commercecore-service:postgresTest
```

다른 서비스·Kafka 검증 조건과 실제 실행 여부는 [도구 안내](../../docs/guides/tooling.md#postgresqlredis-환경)를 따른다. 기본 미들웨어와 격리된 CDC smoke의 포트·DB·브로커를 혼용하지 않는다.

외부 배포에는 새 서비스별 이미지·Deployment·Service·독립 DB/권한/Secret, `/api/catalog`·`/api/commercecore` 라우팅과 내부 Payment 접근을 별도로 반영해야 한다. 이 안내의 로컬 실행은 Kubernetes·운영 배포가 아니다.
