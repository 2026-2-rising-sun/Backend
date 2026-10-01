# P2 백엔드 테스트 가이드

모든 명령은 **Backend 저장소 루트**에서 실행한다. 세션 후속 코드·계약·수동 도구를 함께 반영한 checkout 기준이다.
현재 검증 결과와 미결 항목은 [진행 보고서](progress-report.md)를 확인한다. 명령을 적어 놓은 것과 실제 실행 성공은 구분한다.

## 1. 어떤 검증을 실행할까

| 목적 | 실행 대상 | 증명하는 범위 |
| --- | --- | --- |
| 수정 직후 빠른 회귀 | 해당 Gradle `test` | 단위 테스트와 Spring/H2 API·인가 회귀. JWT 서명은 실제 검증하며 상태 조회 대역은 테스트에만 명시 |
| DB·동시성 검증 | Member/Commerce/Live PostgreSQL 태스크 | Flyway, unique/partial index, 행 잠금, 재고·세션 경합. H2 결과로 대체 불가 |
| 서비스 간 전체 흐름 | `scripts/integration/run.cjs` | 실제 Member/Shopping/Commerce/Live와 새 PostgreSQL DB. 정상·오류 응답 계약, 장애 시 상태 불변 |
| 사람이 API 확인 | `scripts/local/manual-review.cjs` | 개인 테스트 계정과 실제 서비스에 curl/Apidog으로 요청. 자동 테스트 통과를 대신하지 않음 |
| 계약만 확인 | 계약 검사·Prism smoke | YAML 구조·예제·Mock 응답. 실제 인증·트랜잭션 완료 증거는 아님 |

JDK 21과 저장소 Gradle wrapper를 사용한다. API 도구/하네스는 Node.js 22 이상, Docker가 필요하다.
Gateway와 프론트는 필요하지 않다. 전체 하네스의 결제는 MockPaymentEngine, 영상 상태는 local IVS stub이다.

## 2. 일반 테스트와 빌드

```sh
java -version
./gradlew --no-daemon --max-workers=2 :libs:common-security:test
./gradlew --no-daemon --max-workers=2 :services:member-service:test
./gradlew --no-daemon --max-workers=2 :services:shopping-service:test
./gradlew --no-daemon --max-workers=2 :services:commerce-service:test
./gradlew --no-daemon --max-workers=2 :services:live-service:test
```

세션만 빠르게 확인하려면 다음 필터를 사용한다. 최종 확인에서는 해당 서비스 전체 회귀도 실행한다.

```sh
./gradlew --no-daemon --max-workers=2 :services:member-service:test --tests '*MemberSessionTest'
./gradlew --no-daemon --max-workers=2 :libs:common-security:test --tests '*HttpAccessSessionVerifierTest'
./gradlew build --no-daemon --continue --max-workers=2
```

일반 `test`는 순수 단위 테스트만의 개수가 아니다. Member의 PG 태그는 제외되고, Live PG 4개와 Commerce migration PG 3개는 환경을 켜지 않으면 조건부 skip된다. 이를 실제 PG 통과로 세지 않는다.
HTML 보고서는 `<모듈>/build/reports/tests/<태스크>/index.html`, XML은 `<모듈>/build/test-results/<태스크>/TEST-*.xml`이다.
필터 실행은 같은 태스크의 이전 보고서를 교체한다. 서로 다른 실행의 개수를 합쳐 한 번의 성공으로 기록하지 않는다.

## 3. PostgreSQL 검증 — 새 컨테이너에서만

아래 Bash 블록은 임시 PostgreSQL 컨테이너를 만들고 Member/Commerce/Live용 DB를 분리한다.
Live migration 테스트는 `broadcast`를 TRUNCATE하므로 기존 개발 DB 주소를 넣지 않는다.
Member/Commerce는 테스트별 schema도 격리한다. 종료 시 아래 블록에서 만든 컨테이너만 제거한다.

```bash
(
  set -euo pipefail
  P2_PG_NAME="sl-p2-tests-${RANDOM}-$$"
  P2_PG_ID=$(docker run --rm -d --name "$P2_PG_NAME" \
    --label "shoppinglive.test-run=$P2_PG_NAME" \
    -e POSTGRES_PASSWORD=test -e POSTGRES_DB=member_test \
    -p 127.0.0.1::5432 postgres:16-alpine)
  trap 'docker stop "$P2_PG_ID" >/dev/null' EXIT
  P2_PG_PORT=$(docker port "$P2_PG_ID" 5432/tcp | awk -F: '{print $NF}')
  for attempt in {1..60}; do
    if docker exec "$P2_PG_ID" pg_isready -h 127.0.0.1 \
      -U postgres -d member_test >/dev/null 2>&1; then break; fi
    sleep 1
  done
  docker exec -e PGPASSWORD=test "$P2_PG_ID" psql -h 127.0.0.1 \
    -U postgres -d member_test -v ON_ERROR_STOP=1 -c 'SELECT 1'
  docker exec "$P2_PG_ID" createdb -U postgres commerce_test
  docker exec "$P2_PG_ID" createdb -U postgres live_test

  MEMBER_TEST_DB_URL="jdbc:postgresql://localhost:$P2_PG_PORT/member_test" \
  MEMBER_TEST_DB_DRIVER=org.postgresql.Driver \
  MEMBER_TEST_DB_USER=postgres MEMBER_TEST_DB_PASSWORD=test \
    ./gradlew --no-daemon --max-workers=2 :services:member-service:postgresTest

  COMMERCE_TEST_POSTGRES_URL="jdbc:postgresql://localhost:$P2_PG_PORT/commerce_test" \
  COMMERCE_TEST_POSTGRES_USER=postgres COMMERCE_TEST_POSTGRES_PASSWORD=test \
    ./gradlew --no-daemon --max-workers=2 :services:commerce-service:postgresCommerceTest

  LIVE_PG_TEST=1 LIVE_PG_PORT="$P2_PG_PORT" \
    ./gradlew --no-daemon --max-workers=2 :services:live-service:test --tests '*PostgresMigrationTest'
)
```

Member는 회전·재사용, refresh↔logout/탈퇴/강제 폐기 경합을 확인한다. Commerce는 재고·주문·결제·장바구니·migration 회귀를 실행한다. Live는 실제 PG 제약을 확인한다.
PG 태스크에서 skip·0건·필수 suite 누락은 성공이 아니다. CI의 [결과 검사기](../../.github/scripts/check_test_results.py)가 이를 거절한다.

## 4. 실제 서비스 HTTP 통합

확인된 최신 로컬 실행은 `7377ed335ff9e480b0ae9c93f8d6e846b63bdcea`다. 세션 후속을 포함한 check 365개·응답 계약 대조 244개, 실패·skip·미대응 응답 0, 자기 자원 정리가 통과했다. 원격 최종 CI는 별도 대기이며, 이전 dev 기준선의 274개 결과와 합산하지 않는다.

추적·미추적 소스 변경을 모두 커밋한 checkout에서 실행한다. 하네스가 dirty checkout을 거절한다.

```sh
git status --short
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/integration/run.cjs
python3 .github/scripts/check_integration_results.py
```

호스트 JDK 21이 있으면 JVM으로, 없으면 Docker Temurin 21로 네 서비스를 띄운다. Docker 모드를 고정하려면 `SHOPPINGLIVE_INTEGRATION_RUNTIME=docker node scripts/integration/run.cjs`를 사용한다.
새 PostgreSQL DB·loopback 포트·키·계정·서비스 caller를 만들며 기존 Compose/DB에 연결하지 않는다. 종료하면 자기 자원만 정리한다.
현재 네 서비스의 검증 경로에 Kafka 송수신이 없어 Kafka 기동은 제외된다.

정상 회원 구매, 타인 소유권 거부, 재고 경합, 결제 상태 전이, 방송·상품 연결에 이어 다음을 검사한다.

- Prism 404/503 응답과 별도 proxy의 실제 응답 지연, upstream 호출 증거, 실패 전후 DB 불변
- 일반 logout 이후 access 유지, refresh 재사용·동시 소비 이후 family 폐기, 강제 폐기·탈퇴
- Member 중단 시 인증 요청 503·쓰기 차단, 공개 무토큰 조회·내부 caller 독립 동작, 재시작 후 철회 유지
- 실제 HTTP 응답의 같은 Git OpenAPI 대조, 관리 포트 probe와 DB 장애, 자기 자원 정리

결과는 `build/integration/summary.json`과 같은 디렉터리의 정리된 로그에 남는다.
코드 SHA, JAR/계약 hash, 실패·skip, `cleanupPassed`, `implementedFlowsPassed`, `deferred`를 함께 확인한다.
`passed=true`여도 `deferred`가 남으면 `p2Complete=false`다. 원격 CI는 동일 SHA의 build·PG·contracts·integration이 모두 성공해야 통과한다.
`--use-prebuilt`는 같은 SHA의 marker/JAR을 준비한 경우만 사용하며, 다른 코드의 기존 JAR을 재사용하지 않는다.

## 5. 수동 API 검토 환경

```sh
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/local/manual-review.cjs
```

`Manual review ready`가 출력되면 해당 터미널을 계속 켜 둔다. 별도 터미널에서 출력된 절대 경로의 `env.sh`를 읽는다.

```sh
source /absolute/path/from-output/env.sh
curl -i "$MEMBER_URL/v1/members/me" -H "Authorization: Bearer $USER_A_ACCESS_TOKEN"
curl -i "$COMMERCE_URL/v1/cart/items" -H "Authorization: Bearer $USER_A_ACCESS_TOKEN"
curl -i "$SHOPPING_URL/v1/products"
curl -i "$LIVE_URL/v1/broadcasts"
```

주소는 매번 바뀌는 loopback 포트다. `MEMBER_URL`, `SHOPPING_URL`, `COMMERCE_URL`, `LIVE_URL`을 사용한다.
`USER_A_*`, `USER_B_*`, `ADMIN_*`에 이메일·비밀번호·access·refresh가 제공된다. ADMIN은 공개 가입으로 만들지 않는다.
`env.json`은 일반 key/value JSON이며 Apidog 전용 import 파일이 아니다. 필요한 값만 도구의 개인 로컬 환경에 옮긴다.

| 순서 | 요청·확인 | 기대 결과 |
| --- | --- | --- |
| 1 | A/B `GET /v1/members/me`, 무토큰 거래, USER의 관리 API | 본인 프로필 200 / 거래 401 / 관리 403 |
| 2 | ADMIN 이미지 업로드→상품 생성→`POST /v1/sales` 초기 재고→판매 ON_SALE | 생성한 ID 연결, 공개 조회 200. 이미지 필드는 multipart `file` |
| 3 | A `POST /v1/cart/items`→선택 `POST /v1/cart/items/{id}/orders` | 선택 항목만 제거. `X-Idempotency-Key` 필요, 같은 입력 재요청은 같은 주문 |
| 4 | A 주문 결제→상태 조회, B/ADMIN으로 A 주문·결제·장바구니 접근 | Mock 결제 상태 확인, 타인 리소스 404. 일반 결제 body의 `scenario`는 400 |
| 5 | 새 로그인 R0→`POST /v1/auth/refresh`로 R1→다시 R0 사용 | 새 토큰 200, 재사용 401, R1과 해당 access도 이후 요청 거절 |
| 6 | 다른 새 세션에서 `POST /v1/auth/logout` | 204, 해당 refresh 거절, 기존 access는 잔여 만료까지 허용. 타 기기 세션 유지 |
| 7 | ADMIN `POST /v1/admin/members/{id}/sessions/revoke` | 기존 모든 세션 거절, 새 로그인 허용 |
| 8 | `DELETE /v1/members/me`에 비밀번호 확인 | 오답 401/세션 유지, 정답 204/기존 access·refresh·로그인 거절 |
| 9 | ADMIN 방송 생성·상품 연결·시작·종료와 공개 조회 | 실제 도메인 상태 전이 확인. AWS 실제 송출 확인으로 세지 않음 |

정확한 body와 성공 응답 봉투는 [같은 checkout의 YAML](../../contracts/api) 또는 [Swagger](../../contracts/docs/swagger-guide.md)를 따른다. Commerce 일부 API는 성공 DTO를 직접 반환하므로 모든 응답을 `data`로 읽지 않는다.
토큰을 폐기하는 검수는 새 로그인 세션으로 구분한다. refresh 응답을 받으면 개인 환경의 access·refresh를 새 값으로 교체한다.
탈퇴는 검수 후반에 수행하며, 탈퇴한 이메일의 재가입은 허용되지 않는다.

Member 장애 검수와 Ctrl-C 정리 방법은 [수동 검토 도구 설명](../../contracts/docs/manual-review.md)을 따른다.
실행 터미널의 Ctrl-C는 자기 서비스·새 DB·네트워크·키·env 파일을 지운다. `build/manual-review/run-*/logs`에는 정리된 로그만 남으며 자동 통합 결과를 덮어쓰지 않는다.
별도 셸에 읽은 토큰 변수는 프로세스 종료 후에도 남을 수 있으므로 검수 터미널도 닫는다.

## 6. 계약 검증과 문제 진단

```sh
npm run check --prefix scripts/contracts
npm run smoke --prefix scripts/contracts
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```

[Apidog 협업](../../contracts/docs/apidog-team-guide.md) · [Prism 사용](../../contracts/docs/prism-team-guide.md)에서 계약 작성·가져오기·Mock 실행을 확인한다.
Apidog 프로젝트 반영, 실제 결제대행사·IVS·Kubernetes 배포는 위 로컬 테스트 성공만으로 완료되지 않는다.

| 증상 | 먼저 확인할 것 |
| --- | --- |
| 서비스 기동 실패 | 공개 JWKS, Member 개인키/kid, 방향별 caller token, 소비 서비스 `MEMBER_SESSION_BASE_URL` 누락 여부 |
| 서명은 유효한데 401 | sid 없는 이전 토큰, refresh 재사용/강제 폐기/탈퇴 여부. 재로그인하거나 새 검수 환경 사용 |
| 인증 요청만 503 | Member/DB 상태·내부 자격증명·상태 API 응답과 timeout. 무토큰 공개 API 결과도 함께 확인 |
| 일반 테스트의 PG skip | 위 별도 PG 태스크를 실행했는지 확인. 일반 테스트 통과로 대체하지 않음 |
| 통합 시작 전 거절 | dirty checkout 또는 prebuilt SHA 불일치. 소스와 JAR을 맞춘 뒤 새 실행 |
| 이전 정상 결과만 남아 있음 | `summary.json`의 SHA·시작/완료 시각·필수 checks·cleanup을 확인. 실패 로그를 지워 성공으로 만들지 않음 |

비밀번호·JWT·refresh·개인키를 버그 리포트에 붙이지 않는다. 요청 경로, 상태 코드, request-id, 비밀값을 제거한 로그와 해당 SHA를 남긴다.
