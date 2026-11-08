# 분리 서비스를 로컬에서 연결하기

`msa-compose.yml`은 기존 Kind/Infra 환경과 다른 이름·포트·DB를 사용한다. 빈 전용 볼륨에서 Catalog·CommerceCore·Payment 및 검증용 DB를 초기화한다. 앱 포트는 Member8081, Catalog8082, CommerceCore8083, Live8084, Notification8085, Payment8086이다.

```sh
docker compose -f scripts/local/msa-compose.yml up -d --wait
node scripts/local/auth-env.cjs create --access-ttl PT4H --refresh-ttl P1D --out build/local/msa-auth
./gradlew :services:member-service:bootJar :services:catalog-service:bootJar :services:commercecore-service:bootJar :services:payment-service:bootJar :services:live-service:bootJar :services:notification-service:bootJar
```

Java 21을 사용한다. 생성된 `build/local/msa-auth/env.json`은 로컬 키·호출자별 토큰을 포함하며 Git에서 제외된다. `auth-env.cjs exec`는 실행할 서비스에 필요한 인증정보만 전달한다. 아래 명령은 서비스를 띄울 터미널마다 적용하고, 서비스 이름과 jar 경로를 각각 바꿔 실행한다.

```sh
export POSTGRES_PORT=56432 POSTGRES_USER=msa POSTGRES_PASSWORD=msa-local-only
export KAFKA_PORT=59092 REDIS_PORT=59679
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

내부 생성·조회는 Payment `/v1/internal/payments`, `/v1/internal/refunds`를 사용한다. 상품·Payment 결과는 실제 Kafka `localhost:59092`를 통해 전달한다. 기존 판매·쿠폰 토픽은 유지한다. 브라우저의 결제·환불 요청은 Core facade를 거친다.

서비스별 PostgreSQL 업무 회귀는 `CATALOG_TEST_POSTGRES_*`, `COMMERCECORE_TEST_POSTGRES_*`, `PAYMENT_TEST_POSTGRES_*`의 `URL`, `USER`, `PASSWORD`를 지정한 뒤 각 서비스의 `postgresTest`로 실행한다. 기본 검증 DB는 `catalog_test`, `commercecore_test`, `payment_test`다. 병렬 worker는 별도 `catalog_product_test`, `commercecore_product_test`, `commercecore_payment_test`, `payment_execution_test`를 사용한다.

외부 배포에는 새 서비스별 이미지·Deployment·Service·독립 DB/권한/Secret, `/api/catalog`·`/api/commercecore` 라우팅과 내부 Payment 접근을 별도로 반영해야 한다. 이 저장소의 로컬 설정은 Infra 저장소나 배포 환경을 변경하지 않는다.
