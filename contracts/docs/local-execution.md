# P2 로컬 실행과 검증 범위

기본 통합 검증은 Member·Shopping·Commerce·Live를 실제 HTTP로 연결한다.
Prism은 아직 띄우지 않은 의존 서비스를 대체하거나 응답 형태를 재현하는 보조 도구다.
Gateway와 프론트엔드는 이 실행 범위에 포함하지 않는다.

| 모드 | 실제 프로세스와 DB | Mock | 확인할 수 있는 범위 |
|---|---|---|---|
| 서비스 단독 | 검사할 서비스와 전용 PostgreSQL | 필요한 Shopping/Commerce/Live 의존만 Prism | 도메인 로직, 실제 JWT 검증기의 허용·차단. 미리 서명한 테스트 토큰은 로그인 성공 증거가 아님 |
| 실제 인증 결합 | Member와 검사할 서비스, 각각 PostgreSQL | 미기동 의존 서비스만 Prism | 실제 가입·로그인·JWT·권한. Mock 판매/재고 응답은 실제 재고 증거가 아님 |
| 전체 통합 | 네 서비스 및 각각 PostgreSQL | 결제 외부 gateway와 AWS IVS만 기존 Mock/stub | 상품→판매→장바구니→주문→결제 상태·예약/복구·타인 404 등 실제 서비스 흐름 |

서비스 포트는 Member 8081, Shopping 8082, Commerce 8083, Live 8084를 유지한다.
Prism으로 대체한 서비스는 같은 포트를 사용하며 실제 서비스와 동시에 띄우지 않는다.
Swagger는 별도 18090이다. 도구는 기존 Compose·DB·볼륨을 종료하거나 삭제하지 않는다.

## 키·자격증명 준비

Node 22+, Docker(Prism), JDK 21, 각 서비스의 PostgreSQL DB가 필요하다.
서비스 실행은 `local` 프로필이다. `test` 프로필로 보안 필터를 끄지 않는다.
테스트용 토큰을 쓸 때도 `common-security`의 실제 RSA 서명 fixture와 검증기를 사용한다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/local/auth-env.cjs create --access-ttl PT15M --refresh-ttl P30D
```

위 TTL은 이 명령에서 선택한 로컬 실험 입력이다. 운영 기본값·최종 수명 정책을 정하는 값이 아니다.
명령은 TTL 두 값을 생략하면 실패하고, 새 디렉터리에만 PKCS8 private PEM·public JWKS·kid와 호출 방향별 4개 난수 토큰을 만든다.
키·env 파일은 0600, 디렉터리는 0700이며 `build/local` 아래 생성한 파일은 Git에 넣지 않는다.
기존 키를 덮어쓰지 않는다. 새 키로 바꾸면 기존 access JWT 검증이 실패하므로 실행 중인 서비스에 조용히 교체하지 않는다.
출력된 `env.json` 경로를 아래의 `P2_AUTH_ENV`로 사용한다. 실제 토큰 발급이나 계정 생성은 이 명령이 하지 않는다.

```sh
P2_AUTH_ENV=/absolute/path/to/generated/env.json
```

`auth-env.cjs exec <service>`는 해당 서비스가 필요한 키·토큰 설정만 환경변수로 전달하고 셸을 평가하지 않는다.
예시의 `JAVA21_HOME`은 설치된 JDK 21 디렉터리다. Java 26으로 검증된 것으로 간주하지 않는다.
JDK 21이 호스트에 없으면 팀의 JDK 21 컨테이너 빌드/실행 경로를 사용한다. private key와 public JWKS 파일을 실제 컨테이너에서도 읽을 수 있게 mount해야 한다.

```sh
JAVA_HOME="$JAVA21_HOME" ./gradlew :services:member-service:bootJar :services:shopping-service:bootJar :services:commerce-service:bootJar :services:live-service:bootJar
```

기본 local DB 이름은 member/shopping/commerce/live이며 `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`를 명시한다.
공유 개발 DB 대신 전용 DB 또는 스키마를 준비하고 서비스별 `--spring.datasource.url=...`을 별도로 지정할 수 있다.
Flyway는 서비스 기동 때 실행한다. Commerce V3는 기존 비회원 행이 있으면 실패하도록 설계되어 있다.
명시 허용된 local/dev 데이터 전환만 Commerce의 `ops` 절차를 따른다. 이 도구는 기존 행을 자동 삭제하지 않는다.

## 서비스 시작과 의존 전환

각 실제 프로세스는 별도 터미널에서 실행한다. 아래 Member 예시의 서비스를 shopping/commerce/live로 바꾸면 해당 JAR을 실행할 수 있다.

```sh
node scripts/local/auth-env.cjs exec member --env "$P2_AUTH_ENV" -- "$JAVA21_HOME/bin/java" -jar services/member-service/build/libs/member-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

| 실행 대상 | 의존 서비스 주소 옵션 | 실제 HTTP 모드 |
|---|---|---|
| Shopping | `--shopping.sales-client.base-url=http://localhost:8083` | 항상 HTTP |
| Commerce | `--commerce.shopping-client.base-url=http://localhost:8082` | 항상 HTTP |
| Live | `--live.products.shopping-url=http://localhost:8082` 및 `--live.products.commerce-url=http://localhost:8083` | `--live.products.mode=http` |

예를 들어 실제 Member+Shopping만 검증하면 Commerce 프로세스 대신 아래 명령을 실행한다.
Prism은 고정 계약 예시를 반환하며 요청 상품과 일치하는 상태 저장소를 제공하지 않는다. 테스트 fixture의 ID/가격과 선택 예시를 맞춘다.

```sh
node scripts/local/contracts.cjs mock commerce 8083
```

다른 조합도 `mock shopping 8082`, `mock live 8084`처럼 동일한 계약 파일을 사용한다.
실제 서비스를 다시 띄우기 전 해당 Mock 명령에서 Ctrl-C하여 그 컨테이너만 종료한다.
전체 통합에서는 Shopping/Commerce Prism을 종료하고 두 실제 프로세스를 켠다. Live의 상품 클라이언트는 HTTP를 유지한다.
Live local의 IVS stub과 Commerce의 Mock 결제는 실제 AWS 송출·PG 승인 증거로 세지 않는다.

기동 후 `/actuator/health/readiness` 200을 확인한다. DB가 끊기면 readiness는 503이어야 한다.
`/actuator/health/liveness`와 readiness만 인증 없이 허용하며, 나머지 Actuator를 공개 진단 API로 사용하지 않는다.
관리 포트를 분리했다면 해당 management 포트에서 probe를 호출한다.

## 실제 계정과 ADMIN 준비

일반 계정은 실제 Member의 `POST /v1/auth/signup` 201 후 `POST /v1/auth/login` 200으로 만든다.
두 요청은 JSON `{email,password,displayName}`(가입) 및 `{email,password}`(로그인)이며 unknown field는 400이다.
로그인 응답 `data.accessToken`을 `Authorization: Bearer ...`로 보내 `GET /v1/members/me`와 회원 거래 API를 호출한다.
토큰·비밀번호를 계약 예시, Git, 리뷰 로그에 남기지 않는다. signup은 토큰을 발급하지 않는다.
Member의 access/refresh TTL 설정은 필수이고 쿠키 인증은 없다. 현재 refresh는 최초 발급만 존재하며 갱신·로그아웃·탈퇴·소셜은 미구현이다.

ADMIN은 가입 body의 roles나 임의 헤더로 얻을 수 없다. 준비된 Member 스키마에 다음 환경변수를 명시하여 별도 CLI로 신규 ADMIN만 생성한다.

- `MEMBER_BOOTSTRAP_DB_URL`, `MEMBER_BOOTSTRAP_DB_SCHEMA`, `MEMBER_BOOTSTRAP_DB_USER`, `MEMBER_BOOTSTRAP_DB_PASSWORD`
- `MEMBER_BOOTSTRAP_ADMIN_EMAIL`, `MEMBER_BOOTSTRAP_ADMIN_PASSWORD`, `MEMBER_BOOTSTRAP_ADMIN_DISPLAY_NAME`

```sh
"$JAVA21_HOME/bin/java" -jar services/member-service/build/libs/member-service-0.0.1-SNAPSHOT.jar --bootstrap-admin
```

CLI는 HTTP 서버를 띄우지 않고 기존 회원을 승격시키지 않는다. 중복·입력 오류는 exit 1이다.
생성한 ADMIN도 실제 `/v1/auth/login`으로 토큰을 얻는다. 상품/이미지/판매/방송 관리에는 ADMIN이 필요하지만 주문·결제·장바구니의 타인 소유권 404는 ADMIN에도 적용한다.
개발 이미지 정리·결제 시나리오 제어는 별도 local/test opt-in 조건을 충족한 ADMIN만 호출하며 외부 dev/prod에서 금지한다.

## 증거 구분

`npm run check --prefix scripts/contracts`와 `npm run smoke --prefix scripts/contracts`는 계약 형식·예시 증거다.
A5 실제 HTTP harness는 실행 Git SHA·네 YAML hash·실제/Mock 의존 범위·검사 수를 별도 기록한다.
로그인 성공, USER 차단, 타인 객체 404, 재고 부족/실패의 DB 불변, 멱등 replay는 실제 서비스/DB 결과로만 완료 판정한다.
Apidog 프로젝트 연결과 실제 import/export 왕복은 아직 별도 검증 대기이며 실행했다고 기록하지 않는다.
