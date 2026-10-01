# P2 백엔드 진행 보고서

기준일: 2026-10-01. 범위는 Backend와 실행에 필요한 Infra이며 프론트는 제외한다.
사용자 확정: P2는 이메일·비밀번호 방식이며 이메일 형식 검사와 비밀번호 hash DB 저장을 적용한다. 소셜 로그인과 인증메일 발송을 통한 이메일 소유 확인은 이번 범위에서 제외한다.
아래는 **dev에 병합·CI 검증한 기준선**과 **세션 정책 후속 구현**을 구분한 기록이다.
세션 후속의 로컬 전체 통합은 통과했으며, 원격 최종 CI와 P2 미결 항목은 별도로 남아 있다.

## 1. 진행 상태

| 구분 | 확인한 결과 | 남은 확인 |
| --- | --- | --- |
| 병합 기준선 | dev `1891c966`, [CI 36805760933](https://github.com/2026-2-rising-sun/Backend/actions/runs/36805760933) 성공. 실제 HTTP·DB·Prism 검사 274개, 응답 계약 대조 169개, 실패·skip·미대응 응답 0, 자기 자원 정리 성공 | 이 실행은 새 refresh·로그아웃·탈퇴 구현 이전 결과 |
| 세션 공통 검증 | `7bdd9cd`: 공통 테스트 42개 통과. JWT `sid`, 상태 조회·401/503, 실제 read timeout, 응답 검증 | 원격 최종 CI |
| Member 세션 | `def986a`: 일반 테스트 26개, PostgreSQL 테스트 30개 통과, 실패·skip 0 | 원격 최종 CI |
| 소비 서비스 적용 | `66a78dd`: Shopping 19개, Commerce 284개 중 PG 전용 3개 제외, Live 132개 중 PG 전용 4개 제외. 실행한 테스트 실패 0 | 원격 PG job·최종 CI |
| 세션 후속 전체 통합 | `7377ed335ff9e480b0ae9c93f8d6e846b63bdcea`: 실제 HTTP·DB·Prism 검사 365개, 응답 계약 대조 244개, 실패·skip·미대응 응답 0, 자기 자원 정리 성공 | 원격 최종 CI. 이 로컬 실행을 원격 CI 통과로 표시하지 않음 |
| 팀 수동 검토 도구 | `ad14442`에서 실제 네 서비스·새 DB 준비, A/B/ADMIN 프로필과 장바구니 200, foreground 유지·Ctrl-C 자기 자원 정리 확인 | Apidog에서 사람이 수행할 업무별 검수는 별도 |

숫자는 서로 다른 검증 범위다. 일반 테스트와 PostgreSQL은 같은 시나리오를 다시 실행하므로 합산하지 않는다.
HTTP check 365개는 사용자 기능 365개를 뜻하지 않으며, 응답 대조 244개는 그 실행의 일부다. 이전 기준선 274개와도 합산하지 않는다.
이전 결과의 `deferred`에는 당시 미구현이던 세션 정책이 남아 있다. 최신 후속 상태를 이전 JSON에 소급 기록하지 않는다.
해당 `summary.json`은 `passed=true`, `implementedFlowsPassed=true`, `cleanupPassed=true`, `p2Complete=false`다. 실행 당시 `deferred`에는 소셜·Apidog가 있었지만, 이후 사용자 결정으로 소셜은 P2 범위에서 제외됐다. 이전 증거 파일을 고치지 않고 최종 실행에서 범위를 갱신한다.

## 2. 수행한 작업

| 영역 | 변경 내용 | 결과물 |
| --- | --- | --- |
| Member 기본 기능 | 이메일 정규화·중복 방지, BCrypt 비밀번호 저장, USER 고정 가입, 로그인, 본인 프로필 조회·수정, 명시적 ADMIN 생성 CLI | `services/member-service` |
| 세션 생명주기 | access 15분, refresh 로그인 시점부터 절대 30일, hash 저장·회전·재사용 감지, 일반 로그아웃·관리자 강제 세션 폐기·비밀번호 확인 탈퇴 | Member auth 패키지, 새 Flyway migration |
| 공통 인증 | RS256 공개키 검증, issuer/audience/시간/role/sub/sid 검증, 공통 오류 봉투. 각 서비스가 직접 인증 | `libs/common-security` |
| 회원 거래 전환 | 비회원 주문·조회·취소·결제와 주문 비밀번호 제거. Commerce 소유 장바구니, 회원 주문 내역, 본인 소유권 확인 | `services/commerce-service` |
| 기존 기능 인가 | 상품·이미지, 판매 초기 재고·가격·재고·상태, 방송 생성·수정·시작·종료·상품 연결을 ADMIN으로 제한 | 각 서비스 SecurityFilterChain 및 인가 테스트 |
| 내부 호출 | 사용자 JWT와 서비스별 `X-Service-Token` 분리. 내부 상품/판매/세션 조회에 호출자 허용 목록 적용 | 공통 caller 검증, 각 HTTP adapter |
| 계약·Mock | 승인된 OpenAPI YAML을 Swagger와 Prism의 같은 기준으로 사용. 정상·오류 예제 및 실제 제공자 응답 대조 | `contracts/api`, `scripts/contracts`, `scripts/local` |
| 전체 흐름·CI | 실제 네 서비스 HTTP, PG 불변식, Prism 실패·지연 검증. 동일 SHA의 필수 job이 모두 성공해야 이미지 게시 단계 진행 | `scripts/integration`, `.github/workflows/ci.yml` |

상품·이미지·방송 공개 조회는 무토큰으로 유지한다. 장바구니·checkout·주문·결제는 회원만 사용한다.
회원의 주문으로 발생하는 재고 예약·차감·복원은 Commerce 내부 처리이며 ADMIN 역할을 요구하지 않는다.
ADMIN도 다른 회원의 장바구니·주문·결제 소유권을 자동으로 얻지 않는다.

## 3. 특이사항과 설계 판단

1. **즉시 철회 때문에 인증 가용성의 경계가 바뀌었다.** JWT 서명·만료 검증은 각 서비스에서 수행한다. 이후 Member DB의 세션 상태를 확인하며 소비 서비스는 인증된 내부 HTTP, Member 자신은 DB를 사용한다. 유효 상태를 캐시하지 않고, 조회 장애는 503으로 차단한다. 공개 무토큰 조회와 서비스 caller는 이 조회에 의존하지 않는다.
2. **일반 로그아웃과 보안 폐기를 분리했다.** 일반 로그아웃은 refresh family만 폐기하고 이미 발급한 access는 남은 수명 동안 허용한다. 재사용 감지·관리자 강제 폐기·탈퇴는 이후 요청의 access도 거절한다. 이미 인증을 통과해 진행 중인 업무를 취소하는 기능은 아니다. 관리자 강제 세션 폐기는 영구 계정 정지가 아니며 새 로그인은 가능하다.
3. **DB 트랜잭션 안의 폐기를 오류 응답과 분리했다.** 재사용 감지 후 예외 rollback으로 폐기가 취소되지 않도록 처리하고, 회원→family 순서로 잠가 refresh·로그아웃·탈퇴 경합을 검사했다. refresh 만료만으로 아직 유효한 access를 거절하지 않는다.
4. **호환성 변경을 숨기지 않는다.** 새 JWT는 canonical UUID `sid`가 필수다. 기존 family는 새 migration에서 보안 폐기되어 재로그인이 필요하다. 비회원 주문의 기존 행은 임의 회원에게 배정하지 않으며, migration은 기존 데이터 정책을 명시적으로 적용해야 진행된다.
5. **JSON refresh를 사용한다.** refresh는 요청·응답 JSON으로 전달하며 인증 쿠키는 사용하지 않는다. 브라우저용 토큰 보관·프론트 연결은 이번 범위가 아니다. 개인정보·토큰·개인키는 로그 및 공유 산출물에 남기지 않는다.
6. **실패 응답과 네트워크 장애를 구분해 검증했다.** Prism의 503 예제와 실제 응답 헤더 지연으로 발생한 read timeout은 별도 시나리오다. upstream 호출 흔적과 실패 전후 DB 상태까지 확인한다. Prism은 JWT 검증·DB 상태 전이·동시성을 증명하지 않는다.
7. **CI 자체의 누락도 검사한다.** 빈 결과, 필수 서비스·오류 시나리오 누락, PG suite 미실행, 다른 SHA의 결과, 정리 실패를 성공으로 인정하지 않는다. PG 준비 단계는 초기 Unix socket 서버 대신 최종 TCP 연결과 SQL 준비를 확인하도록 보완했다.

## 4. 이력서에 사용할 수 있는 키워드와 근거

| 키워드 | 설명 가능한 작업·근거 | 표현의 경계 |
| --- | --- | --- |
| JWT 인증·도메인 인가 | RS256/JWKS, USER·ADMIN·서비스 caller 분리, 타인 리소스 404, 실제 서명 토큰을 이용한 거부 테스트 | 운영 공격 방어율·성능 개선 수치 없음 |
| Refresh rotation·재사용 차단 | hash 저장, 절대 만료, 동일 family 동시 소비와 logout/withdraw 경합을 실제 PG에서 검증. 새 전체 HTTP 실행에서도 이후 access 거절 확인 | 로컬 통합과 원격 CI·운영 검증을 구분 |
| 트랜잭션·소유권·멱등성 | 회원별 멱등키, 같은 키의 다른 입력 409, 선택 장바구니 주문과 재고 처리, 다른 회원 결과 누출 방지 | 서비스 전체의 exactly-once 보장이라고 확대하지 않음 |
| 안전한 스키마 전환 | 비회원 데이터 정책을 명시한 Flyway 전환, 임의 회원 배정 금지, 테스트별 schema/DB 격리 | 운영 무중단 migration을 실증한 것은 아님 |
| 계약 기반 테스트 | OpenAPI 정상·오류 예제, 동일 YAML의 Prism/Swagger, 실제 HTTP 응답 schema 검증 | Apidog 프로젝트 반영·자동 양방향 동기화 완료 주장 금지 |
| 장애 주입·CI 품질 게이트 | 실제 의존 서비스 중단·timeout과 DB 불변식, 필수 결과·SHA·정리 검증 | 처리량·응답 속도 향상 및 운영 SLA를 측정한 것은 아님 |

문장 예: “회원별 소유권과 멱등키 범위를 적용해 장바구니·주문·결제를 회원 전용으로 전환하고, 실제 PostgreSQL 및 서비스 간 HTTP 테스트로 타인 접근·재고 경합·의존 서비스 장애를 검증했다.”
세션 문장 예: “refresh 재사용·강제 세션 폐기·탈퇴에 따른 access 철회를 구현하고, 실제 서비스 HTTP 테스트로 각 서비스의 거절과 Member 장애 시 503·쓰기 차단을 확인했다.” 원격 운영 성과로 확대하지 않는다.

## 5. 결과물 찾기

- [단위·통합·수동 테스트 가이드](testing-guide.md)
- [실제 HTTP 하네스](../../scripts/integration/README.md): 같은 SHA의 `build/integration/summary.json`, JAR·계약 hash, 정리된 로그
- [OpenAPI 계약](../../contracts/api): 서비스별 YAML과 정상·오류 예제
- [Swagger 사용](../../contracts/docs/swagger-guide.md) · [Prism 사용](../../contracts/docs/prism-team-guide.md) · [Apidog 협업](../../contracts/docs/apidog-team-guide.md)
- [수동 검토 환경](../../contracts/docs/manual-review.md): 새 환경, 개인 계정·토큰, 종료·정리 방법
- CI artifacts: `unit-test-reports-<sha>`, `postgres-<service>-<sha>`, `contracts-<sha>`, `integration-<sha>`

## 6. P2 미결과 P3 제안

**P2 미결:** 새 세션 후속 코드의 원격 최종 CI 검증과 실제 팀 Apidog 프로젝트 import/export 확인이다. 소셜 로그인은 제외된 기능이며 P2 미완료 사유로 세지 않는다. 원격 배포, 실제 결제대행사 결제, AWS IVS 송출 완료는 이번 결과에 포함하지 않는다.

P3는 다음 조건을 확인한 뒤 별도 작업으로 진행한다.

| 제안 | 착수 조건·완료 기준 |
| --- | --- |
| API Gateway | 외부 경로·TLS·CORS·공통 제한을 한 진입점에서 운영할 필요가 생겼을 때 도입. 실제 라우팅, multipart·query 전달, 내부/dev 경로 차단 검증. 각 서비스 JWT·소유권 검증은 유지 |
| 배포 전환 절차 | 기존 guest 데이터·이전 sid 없는 세션 처리, migration 백업/복구, 신구 서비스 호환성과 롤백 한계를 확인한 뒤 dev rollout |
| 보안 운영·관측 | 로그인 실패·refresh reuse·상태 조회 503 지표와 비밀값 없는 감사 기록, 비정상 요청 제한·알림·Secret 운영 절차 |
| 키 교체 | 검증 서비스에 신구 공개키 배포→Member 새 kid 서명→기존 access 만료 후 옛 키 제거. 파일 기반 키는 재시작 때 읽으므로 실제 교체/복구 연습 필요 |
| 인증 가용성 | Member/DB 지연과 중단이 인증 요청에 미치는 영향을 관측. 즉시 철회 정책을 유지한 채 용량·복구 방식을 검토하며, 임의 캐시·장애 시 통과로 바꾸지 않음 |

소셜 로그인·인증메일 발송은 별도 요구가 생기면 범위를 다시 합의한다. 프론트 작업은 이 보고서의 후속 범위에도 포함하지 않는다.
