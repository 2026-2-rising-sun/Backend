# #347 기존 Mock 결제 코드 제거

상태: 설계 확정(2026-11-15) · #343을 기다리지 않는다. #343이 먼저 병합되면 아래 표대로 바꾼다
이슈: [#347](https://github.com/2026-2-rising-sun/Backend/issues/347) · Phase 문서: [Phase 1 결제 1차](../phase1-payment.md)

## 개요

끝나면 payment-service에 **기존 Mock 결제·환불 코드와 그 API가 없고**, 서비스는 컨트롤러 없이 기동한다. 새 코드(#348 이후)가 이름·경로·테이블에서 옛 코드와 섞이지 않게 하는 것이 목적이다. 기존 테이블은 이 PR에서 지우지 않는다. 테이블은 #348의 V4가 지운다(마이그레이션 번호를 하나로 모으기 위해).

## 범위

요구사항 ID 없음(삭제 작업). 근거: [ADR 0337-2](../../../../adr/0337-2-payment-replacement.md) "기존 구현과 호환을 맞추지 않고 교체한다".

하지 않는 것: CommerceCore 결제 모듈(Phase 2), DB 테이블 삭제(#348 V4), 이벤트 계약 YAML 교체(#338).

## 삭제 목록 (2026-11-15 `dev` 기준)

경로 접두: `services/payment-service/src/`

| 구분 | 파일 |
|---|---|
| 컨트롤러 | `main/java/com/shoppinglive/payment/controller/InternalPaymentController.java`, `PaymentScenarioController.java` |
| DTO | `main/java/com/shoppinglive/payment/dto/` 전체(`DevPaymentScenarioRegistry`, `MockRefundResult`, `PaymentCommand`, `PaymentExecution`, `RefundCommand`, `RefundExecution`) |
| 엔티티 | `main/java/com/shoppinglive/payment/entity/` 전체(`MockPaymentResultUnknownException`, `MockRefundOutcome`, `PaymentAttempt`, `PaymentScenario`, `PaymentStatus`, `RefundStatus`) |
| 예외 | `main/java/com/shoppinglive/payment/exception/MockRefundResultUnknownException.java` |
| 리포지토리 | `main/java/com/shoppinglive/payment/repository/PaymentAttemptJpaRepository.java` |
| 서비스 | `main/java/com/shoppinglive/payment/service/` 전체(Mock 엔진·게이트웨이, 결과 발행, 재시도 정책, 복구 저장소, 환불 접수·실행·결과, reconciler 등 17개) |
| 관측 | `main/java/com/shoppinglive/payment/observability/` 전체(`PaymentObservabilityConfiguration`, `PaymentObservation`, `PendingWorkMetrics` — 옛 테이블을 센다) |
| 지원 | `main/java/com/shoppinglive/payment/support/AfterCommit.java`(옛 서비스 2곳만 사용) |
| 단위 테스트 | `test/java/com/shoppinglive/payment/service/` 전체 4개 |
| 통합 테스트 | `integrationTest/java/com/shoppinglive/payment/service/` 전체 5개 |
| 설정 | `application.yml`의 `payment.refunds.execution-enabled` |

## 남길 것

| 파일 | 이 PR에서 바꾸는 것 |
|---|---|
| `PaymentServiceApplication.java` | 없음 |
| `config/PaymentSecurityConfiguration.java` | 허용 목록을 비운다. `/v1/internal/**`는 모두 `denyAll`(#348에서 새 경로를 연다). 서비스 토큰 필터·probe 체인은 유지 |
| `config/PaymentExceptionHandler.java` | 없음(#348이 `PaymentError` 처리를 더한다) |
| `integrationTest/.../PaymentSecurityIntegrationTest.java` | 다시 쓴다: 토큰 없음 401, 토큰 있어도 `/v1/internal/payments` 403, `/actuator/health/liveness`·`/actuator/health/readiness` 200(기존 probe 검사 유지) |
| `db/payment-migration/V1–V3` | 없음 |
| `application.yml`의 datasource·kafka·flyway·outbox 설정 | 없음 |
| `build.gradle.kts` | `resilience4j-spring-boot3`(지금도 쓰는 곳 없음)와 `spring-boot-starter-aop`(지우는 `PaymentObservation`만 씀) 두 줄을 지운다. 새 설계의 재호출은 1번뿐이라 라이브러리가 필요 없다 |

기동 확인을 위해 컨텍스트 로드 테스트 1개(`PaymentServiceContextTest`)를 둔다. `ddl-auto: validate`는 엔티티가 없으므로 통과한다.

## 계약

| 파일 | 이 PR |
|---|---|
| `contracts/api/payment-service.yaml` | `paths: {}`로 비운다(검사기는 `paths` 키만 요구한다). `components`는 #348이 다시 쓴다 |
| `docs/design-docs/services/payment/api.html` | `scripts/render-api-docs.sh`로 재생성 |
| `contracts/events/payment-execution-results.yaml` | **그대로 둔다.** 발행 코드는 지우지만 CommerceCore 소비자가 아직 쓴다. 교체는 #338 PR5 |
| `contracts/events/payment-refund-results.yaml` | 그대로 둔다. 교체는 Phase 3 |

## #343에 따라 바뀌는 것

| #343 결과 | 이 설계 |
|---|---|
| Payment 부분을 빼고 병합(요청한 안) | 위 목록 그대로 |
| Payment 부분을 넣은 채 먼저 병합 | 삭제 목록에 `dto/OrderClosureCommand`, `order-closure` 엔드포인트, 관련 테스트를 더한다. 그 PR의 `V4__order_closure_compensation.sql`이 먼저 들어가므로 **#348의 마이그레이션을 `V5__payment_core.sql`로 바꾸고**, 맨 앞 DROP에 `payment_order_control`을 더한다 |
| 아직 열려 있음 | **기다리지 않고 진행한다**(2026-11-15 사용자 결정). 충돌은 #343이 맞춘다 |

## 테스트

| 확인 | 방법 |
|---|---|
| 컨트롤러 없이 기동 | `PaymentServiceContextTest` |
| 보안 체인 유지 | `PaymentSecurityIntegrationTest`(다시 씀) |
| 전체 검사 | `scripts/check.sh --base origin/dev`. CommerceCore 테스트가 깨지면 그 부분만 남기고 PR 본문에 적는다 |

## 바뀌는 점

| 구분 | 유지 | 없어짐 |
|---|---|---|
| 코드 | 애플리케이션, 보안·예외 설정 | Mock 결제·환불 전체, 관측 지표 |
| API | — | `/v1/internal/payments*`, `/refunds*`, `/payment-scenarios/*` 전부 |
| DB | 테이블 그대로(지우는 것은 #348) | — |
| 이벤트 | 계약 YAML 그대로 | `PaymentExecutionResolved` 발행 코드 |
| 운영 | — | **이 PR 병합부터 Phase 2가 끝날 때까지 통합 환경의 결제가 동작하지 않는다.** CommerceCore의 결제 호출은 403을 받는다 |

## 문서 갱신

- `docs/design-docs/services/payment/README.md` "현재 코드" 절: Mock 코드 삭제 사실과 이 PR 링크
- `erd.md`·`states/payment.md`·`states/refund.md`: "교체 예정" 표시를 "코드 삭제됨, 테이블은 #348 V4에서 삭제" 로 바꾸고 HTML 재생성
- `design-docs/order-payment.md`·`refund.md`, `requirements/order-payment.md`: Payment 쪽 설명에 같은 표시

## PR 나누기

| PR | base | 브랜치 | 크기 |
|---|---|---|---|
| PR0 기존 코드 삭제 | `dev` | `refactor/#347-payment-legacy-removal` | XL 가능(삭제 줄). PR 본문에 이유 한 줄 |

PR0이 #347을 `Closes`한다.

## 미결정

결정(2026-11-15, 사용자 동의): 쓰지 않는 의존성 두 줄은 이 PR에서 지운다.

## 참고

[ADR 0337-2](../../../../adr/0337-2-payment-replacement.md) · [#343 조율 댓글](https://github.com/2026-2-rising-sun/Backend/pull/343#issuecomment-6892067416) · [decision.md](../decision.md)
