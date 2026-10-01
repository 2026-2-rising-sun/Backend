# 직접 수동 검수하기

아래 도구는 전용 환경을 새로 띄운다. 기존 개발 DB·Compose를 사용하지 않으며 자동 통합 테스트 완료를 대신하지 않는다.
프론트와 Gateway는 제외한다. 결제는 기존 MockPaymentEngine, 영상은 local IVS stub이다.

## 준비

- Node.js 22 이상, 실행 중인 Docker Desktop, 저장소 checkout.
- JDK 21이 있으면 host JVM, 없으면 Docker Temurin 21을 자동 선택한다. 최초 실행에는 이미지·Gradle 의존성 다운로드가 필요하다.
- API 도구는 Apidog·Bruno·curl 중 하나면 된다. PostgreSQL 4개 DB, 테스트 서명키, caller 자격증명, 일반 회원 A/B와 ADMIN은 도구가 만든다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/local/manual-review.cjs
```

터미널에서 `Manual review ready`가 나올 때까지 기다리고 **계속 켜 둔다**.
현재 checkout으로 네 서비스 jar를 빌드한다. 명시적 `--use-prebuilt`는 checkout SHA와 `build/integration-jars.sha`가 같은 jar에만 허용한다.
`--verify-setup`은 준비 성공 후 즉시 정리하는 도구 검증 모드이며 사람이 계속 사용할 환경을 남기지 않는다.

## 주소와 계정

매번 충돌 없는 loopback 포트를 선택한다. 출력된 `env.sh` 경로를 별도 터미널에서 읽는다.

```sh
source /absolute/path/from-output/env.sh
curl -i "$MEMBER_URL/v1/members/me" -H "Authorization: Bearer $USER_A_ACCESS_TOKEN"
```

| 환경변수 | 용도 |
|---|---|
| `MEMBER_URL`, `SHOPPING_URL`, `COMMERCE_URL`, `LIVE_URL` | 실제 API 주소 |
| `*_HEALTH_URL` | 분리한 관리 포트 주소 |
| `USER_A_*`, `USER_B_*`, `ADMIN_*` | EMAIL, PASSWORD, ACCESS_TOKEN, REFRESH_TOKEN. 일반 회원은 ID도 제공 |
| `*_CONTAINER` 또는 `*_PID` | 이 실행에서만 만든 서비스의 장애 검수 대상 |

`env.json`은 같은 값을 담은 일반 key/value JSON이다. **Apidog 전용 import 형식이 아니다.** 사용하는 도구의 개인 로컬 환경변수에 필요한 값만 옮긴다.
키·토큰·비밀번호는 0700 디렉터리/0600 파일에만 저장하며 공유 환경, Git, 채팅, 스크린샷에 올리지 않는다.
기본 access는 15분이다. refresh 교체 후에는 응답의 새 토큰으로 개인 환경값을 갱신한다. 오래된 토큰을 재사용하면 보안 폐기 검수가 된다.

## 권장 검수 순서

정확한 요청 JSON·응답 예시는 같은 checkout의 `contracts/api/*.yaml` 또는 [Swagger](swagger-guide.md)를 따른다.

| 순서 | 직접 확인할 동작 | 기대 결과 |
|---|---|---|
| 1 | A/B 프로필 조회, 일반 회원의 ADMIN API 호출 | 본인 정보 200, 일반 회원 403, 무토큰 관리 요청 401 |
| 2 | ADMIN 이미지 업로드→상품 등록→판매가/재고/판매 상태 설정 | 생성한 ID로 연결. 무토큰 공개 상품 조회 성공 |
| 3 | A 장바구니 여러 상품→선택한 항목 주문→결제 | 선택 항목만 제거, 나머지 보존. 같은 멱등키 replay는 같은 주문 |
| 4 | B와 ADMIN으로 A 주문·결제·장바구니 접근 | 타인 데이터 404 |
| 5 | 새 로그인 세션으로 refresh 교체 후 이전 refresh 재사용 | 교체 200, 재사용 401, 해당 family의 access 이후 검사 401 |
| 6 | 다른 새 세션의 일반 logout | 204, refresh 사용 거부. 기존 access는 남은 수명 동안 허용 |
| 7 | ADMIN 강제 세션 폐기, 다른 회원의 비밀번호 확인 탈퇴 | 기존 access 이후 검사 401. 강제 폐기는 새 로그인을 막는 계정 정지가 아님 |
| 8 | ADMIN 방송 생성·상품 연결·시작·종료 | 공개 조회와 실제 상품/판매 조회. AWS 송출 성공으로 세지 않음 |

실패를 의도한 단계마다 새 로그인 세션을 구분한다. 탈퇴한 계정은 같은 이메일로 재가입할 수 없으므로 검수 순서 후반에 사용한다.

## Member 장애 검수

Docker 모드라면 아래 변수는 **이 실행에서 생성한 Member 컨테이너**만 가리킨다.

```sh
docker pause "$MEMBER_CONTAINER"
curl -i "$COMMERCE_URL/v1/cart/items" -H "Authorization: Bearer $USER_B_ACCESS_TOKEN"
docker unpause "$MEMBER_CONTAINER"
```

아직 폐기하지 않은 B 세션을 사용한다. Member 확인이 불가하면 503 `SERVICE_UNAVAILABLE`여야 한다.
무토큰 공개 조회는 Member 확인에 의존하지 않는다. 실제 판매/상품 서비스가 필요한 경로는 그 서비스가 정상이어야 한다.
Host JVM 모드에서는 `kill -STOP "$MEMBER_PID"`, 검수 후 `kill -CONT "$MEMBER_PID"`로 같은 과정을 수행한다.
별도 관리 포트 readiness/liveness도 확인한다. 이 검수는 Kubernetes 배포·장애 복구 검증이 아니다.

## 종료

실행 터미널에서 Ctrl-C한다. 이번 실행의 서비스·PostgreSQL·네트워크·임시 키·env 파일만 삭제한다.
의도적으로 pause한 자원도 자신의 소유인지 확인한 뒤 재개하고 정리한다. DB는 tmpfs라 다음 실행은 빈 DB와 새 계정으로 시작한다.
`build/manual-review/run-*/logs`에는 비밀값을 제거한 로그와 준비 메타데이터만 남는다. 자동 통합 검증의 `build/integration` 증거는 덮어쓰지 않는다.
도구가 종료되어도 별도 셸에 `source`한 변수는 남을 수 있으므로 검수용 터미널을 닫는다.
