# #347 기존 Mock 결제 코드 제거

상태: 설계 확정(2026-11-15) · #343을 기다리지 않는다. #343이 먼저 병합되면 아래 표대로 바꾼다
이슈: [#347](https://github.com/2026-2-rising-sun/Backend/issues/347) · Phase 문서: [Phase 1 결제 1차](../phase1-payment.md)

## 개요

끝나면 payment-service에 **기존 Mock 결제·환불 코드와 그 API가 없고**, 서비스는 컨트롤러 없이 기동한다. 새 코드(#348 이후)가 이름·경로·테이블에서 옛 코드와 섞이지 않게 하는 것이 목적이다. 기존 테이블은 이 PR에서 지우지 않는다. 테이블은 #348의 V4가 지운다(마이그레이션 번호를 하나로 모으기 위해).

## 범위

요구사항 ID 없음(삭제 작업). 근거: [ADR 0337-2](../../../../../adr/0337-2-payment-replacement.md) "기존 구현과 호환을 맞추지 않고 교체한다".

하지 않는 것: CommerceCore 운영 코드(`src/main`)는 건드리지 않고 테스트 결합만 끊는다(결제 모듈 교체는 Phase 2). DB 테이블 삭제(#348 V4), 이벤트 계약 YAML 교체(#338).

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
| 단위 테스트 | `test/java/com/shoppinglive/payment/service/`의 `PaymentRetryPolicyTest`, `RefundMockEngineTest`, `RefundRetryPolicyTest`. `ExecutionEventContractTest`는 지우지 않고 다시 쓴다(아래 "남길 것") |
| 통합 테스트 | `integrationTest/java/com/shoppinglive/payment/service/` 전체 5개 |
| 설정 | `application.yml`의 `payment.refunds.execution-enabled` |
| CommerceCore 테스트 | 아래 "CommerceCore 테스트 정리" |

## 남길 것

| 파일 | 이 PR에서 바꾸는 것 |
|---|---|
| `PaymentServiceApplication.java` | 없음 |
| `config/PaymentSecurityConfiguration.java` | 허용 목록을 비운다. `/v1/internal/**`는 모두 `denyAll`(#348에서 새 경로를 연다). 서비스 토큰 필터·probe 체인은 유지 |
| `config/PaymentExceptionHandler.java` | 없음(#348이 `PaymentError` 처리를 더한다) |
| `integrationTest/.../PaymentSecurityIntegrationTest.java` | 다시 쓴다: 토큰 없음 401, 토큰 있어도 `/v1/internal/payments` 403, `/actuator/health/liveness`·`/actuator/health/readiness` 200(기존 probe 검사 유지) |
| `test/.../service/ExecutionEventContractTest.java` | 루트 패키지로 옮기고 옛 DTO 없이 `PaymentExecutionResolved` 봉투 표본 JSON만 만들게 다시 쓴다. 계약 YAML이 남아 있고, Payment만 빌드하는 CI에서 `check_events.py --samples`가 표본 0건으로 실패하지 않게 한다. #338 승인 처리 PR에서 `PaymentApprovedContractTest`로 바꾼다 |
| `db/payment-migration/V1–V3` | 없음 |
| `application.yml`의 datasource·kafka·flyway·outbox 설정 | 없음 |
| `build.gradle.kts` | `resilience4j-spring-boot3`(지금도 쓰는 곳 없음)와 `spring-boot-starter-aop`(지우는 `PaymentObservation`만 씀) 두 줄을 지운다. 새 설계의 재호출은 1번뿐이라 라이브러리가 필요 없다 |

기동 확인을 위해 컨텍스트 로드 테스트 1개(`PaymentServiceContextTest`)를 둔다. `ddl-auto: validate`는 엔티티가 없으므로 통과한다.

PostgreSQL 테스트를 모두 지우면 CI가 고르는 `postgresTest`·`migrationTest`가 0건이 된다(지우는 파일에 `migration` 태그가 있어 `migrationTest`도 선택된다). 두 태스크는 0건이면 실패하므로 위 "테스트" 표의 Postgres 테스트 2개를 새로 둔다.

## 계약

| 파일 | 이 PR |
|---|---|
| `contracts/api/payment-service.yaml` | `paths: {}`로 비운다(검사기는 `paths` 키만 요구한다). `components.schemas`도 지운다: 지운 경로의 YAML 앵커(`&id001` 등)를 참조해 경로만 지우면 파싱이 깨지고, 지운 DTO를 설명한다. `securitySchemes`는 최상위 `security`가 참조하므로 남긴다. `components`는 #348이 다시 쓴다 |
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
| 실제 PostgreSQL·Flyway·`ddl-auto: validate`로 기동 | `PaymentStartupPostgresTest`(`@Tag("postgres")`). H2 create-drop은 validate 통과를 증명하지 못한다 |
| V1–V3가 빈 스키마에 적용 | `PaymentMigrationPostgresTest`(`@Tag("postgres")`·`@Tag("migration")`) |
| 이벤트 표본 0건 방지 | `ExecutionEventContractTest`(다시 씀) |
| 전체 검사 | `scripts/check.sh --base origin/dev`. Core 테스트 정리로 Core 빌드가 선택된다. 지운 Core 테스트는 PR 본문에 적는다("CommerceCore 테스트 정리") |

## 바뀌는 점

| 구분 | 유지 | 없어짐 |
|---|---|---|
| 코드 | 애플리케이션, 보안·예외 설정 | Mock 결제·환불 전체, 관측 지표 |
| API | — | `/v1/internal/payments*`, `/refunds*`, `/payment-scenarios/*` 전부 |
| DB | 테이블 그대로(지우는 것은 #348) | — |
| 이벤트 | 계약 YAML 그대로 | `PaymentExecutionResolved` 발행 코드 |
| 운영 | — | **이 PR 병합부터 Phase 2가 끝날 때까지 통합 환경의 결제가 동작하지 않는다.** CommerceCore의 결제 호출은 403을 받는다. 로컬 개발용 Core `/v1/dev/payment-scenarios`도 실패한다(`DevPaymentScenarioRegistry`가 `HttpPaymentClient.setScenario/clearScenario`로 Payment `/v1/internal/payment-scenarios`를 부르고 403을 받는다. Core 코드는 고치지 않는다) |

## 문서 갱신

- `docs/design-docs/services/payment/README.md` "현재 코드" 절: Mock 코드 삭제 사실과 이 PR 링크
- `erd.md`·`states/payment.md`·`states/refund.md`: "교체 예정" 표시를 "코드 삭제됨, 테이블은 #348 V4에서 삭제" 로 바꾸고 HTML 재생성
- `design-docs/order-payment.md`·`refund.md`, `requirements/order-payment.md`: Payment 쪽 설명에 같은 표시
- 지운 파일을 가리키던 링크(`payment/erd.md`·`states/payment.md`·`states/refund.md`, `commercecore/states/order.md`·`payment.md`·`refund.md`): 파일 이름만 남기고 "(#347에서 삭제)"로 바꾼 뒤 HTML 재생성
- `services/payment-service/AGENTS.md`의 "기존 코드 삭제 PR이 병합되면 이 줄을 지운다" 줄 삭제
- `scripts/local/README.md`의 Payment 내부 경로 설명: 경로가 없고 Core 결제 호출이 403을 받는다고 고친다

## CommerceCore 테스트 정리

결정(2026-11-15, 사용자 "응, 끊고 통보"): 이 PR에서 CommerceCore 테스트와 옛 Payment의 결합을 끊는다. 통보는 PR 본문과 CommerceCore 담당자에게 한다.

이유: Core `build.gradle.kts`의 `testImplementation(project(":services:payment-service"))`로 Core 테스트가 옛 Payment 서버를 별도 컨텍스트로 띄웠다(`PaymentClientTestConfiguration`). 모든 Core 통합 테스트의 기반 클래스 `CommerceCoreSecurityTestSupport`가 이 fixture를 import한다. 그래서 옛 코드를 지우면 결제와 무관한 테스트까지 Core 테스트 소스셋 전체가 컴파일되지 않는다. Payment만 바뀐 PR에서는 CI가 Core를 빌드하지 않아 이 문제를 잡지 못한다.

바꾼 것(Core `src/main`은 그대로):

- `build.gradle.kts`의 `testImplementation(project(":services:payment-service"))` 삭제
- `src/test` fixture 4개 삭제: `PaymentClientTestConfiguration`, `PaymentBoundaryReconciler`, `RefundBoundaryReconciler`, `RefundBoundaryTestDriver`. `CommerceCoreSecurityTestSupport`는 fixture import만 뺀다
- `src/integrationTest/resources/application.yml`에 `commercecore.payment-client.base-url: http://127.0.0.1:1`을 둔다. fixture가 없으면 Core 결제 호출이 기본값 `localhost:8086`으로 간다. 로컬에서 띄운 Payment에 테스트가 닿지 않게 막는다
- `src/integrationTest`의 `payment/**`에서 옛 Payment 클래스나 fixture를 쓰는 테스트 16개 파일 삭제. 남긴 것: `RefundApiDisabledTest`, `RefundApiRequestValidationTest`, `RefundSchemaPostgresTest`, `PaymentDatabaseTestSupport`(주문 테스트가 쓴다)
- `RefundTestSupport`: 처음엔 지웠으나, dev 병합으로 들어온 `OrderProcessingDeadlinePostgresTest`(Payment 클라이언트를 spy로 끊고 Core 기한만 검사)가 쓰므로 옛 Payment 부분(`paymentServer`, `attempts`, `MockPaymentEngine`, `createAndPay*`·`submitRefund`·`makeRefundDue`)만 뺀 채 남긴다. 그 테스트의 spy 대상은 fixture 빈 `boundaryPaymentClient`에서 Core 빈 `paymentClient`로 바꾼다
- dev 병합으로 들어온 PostgreSQL 기반 클래스 `OrderSheetPostgresSupport`: `refundDriver`·`paymentServer` 주입, Payment DB 행 정리, `PAYMENT_TEST_POSTGRES_URL` 전제를 뺀다. `CommerceCoreSecurityTestSupport`의 쓰이지 않는 fixture 주입 필드도 뺀다

**"복구할 회귀"로 표시한 항목은 주문 연동에서 새 Payment 계약으로 다시 만든다.** 지운 파일 16개의 메서드는 모두 복구할 회귀다. "되살림"은 리뷰 뒤 결제 없이 성립하는 부분만 다시 넣은 것이다.

### 지운 테스트 파일 (16개, 메서드 86개)

2026-11-17 dev 병합(#361·#363·#365·#314)에서 이 파일들에 메서드 2개가 더해졌다(아래 "dev 병합 때 추가"). dev 정리로 먼저 사라진 테스트는 없다.

| 파일 | 메서드 | 검사하던 것 |
|---|---|---|
| `RefundApiPostgresTest` | `memberAndSellersSeeOnlyTheirOwnRefundProjection` | 회원과 판매자는 각자에게 속한 환불 정보만 조회한다 |
| `RefundApiPostgresTest` | `ownUnknownRecoveryIsVisibleWithoutLeakingKeysLeasesOrAnotherSellersMoney` | 본인 미확정 환불을 조회하되 키·작업 임대·다른 판매자 금액은 노출하지 않는다 |
| `DurableMockRefundGatewayPostgresTest` | `sameRequestReusesOneImmutableGatewayResult` | 같은 요청은 변경되지 않는 게이트웨이 결과 하나를 재사용한다 |
| `DurableMockRefundGatewayPostgresTest` | `persistedSuccessSurvivesLostResponseAndRetry` | 성공 응답 유실 후 재시도해도 저장된 성공 결과를 유지한다 |
| `DurableMockRefundGatewayPostgresTest` | `missingResultCanBeRetriedWithoutInventingAnOutcome` | 결과가 없으면 결과를 임의로 만들지 않고 재시도를 허용한다 |
| `DurableMockRefundGatewayPostgresTest` | `replayRejectsAnAmountDifferentFromThePersistedRequest` | 재요청 금액이 저장된 요청과 다르면 거절한다 |
| `NotificationEventPostgresTest` | `cancellationAndExpiryRollbackTogetherWithEvents` | 취소·만료 이벤트 기록 실패는 주문 종료와 재고 복구도 롤백한다 |
| `NotificationEventPostgresTest` | `staleLeaseAndConcurrentRecoveryDoNotDuplicatePaymentEvents` | 실행 권한을 잃은 결제는 발행하지 않고 동시 복구는 성공 이벤트 한 건만 기록한다 |
| `NotificationEventPostgresTest` | `recordsCreationOnceForSingleAndCartOrders` | 단건과 장바구니 생성·재요청은 실제 생성마다 이벤트 한 건만 기록한다 |
| `NotificationEventPostgresTest` | `unknownThenSuccessRecordsOnlyOneConfirmedResult` | 미확정 결제에는 결과 이벤트가 없고 복구 성공을 중복 발행하지 않는다 |
| `NotificationEventPostgresTest` | `recordsFailureCancellationAndExpiryOnceEach` | 실패·취소·만료의 실제 전이에만 이벤트를 한 건씩 기록한다 |
| `NotificationEventPostgresTest` | `zeroPaymentPublishesOnceWithDiscountedAmounts` | 전액 할인 결제도 성공 이벤트 한 건과 0원 결제액을 기록한다 |
| `NotificationEventPostgresTest` | `partialRefundsPublishPerSuccessfulRequestWithCumulativeAmount` | 부분 환불 요청마다 금액과 성공 누적액을 기록하고 재실행은 발행하지 않는다 |
| `NotificationEventPostgresTest` | `outboxFailureRollsBackBusinessChangesAndAllowsRecovery` | 이벤트 기록 이후 오류가 나면 생성·결제·환불 상태와 Outbox를 함께 롤백한다 |
| `NotificationEventPostgresTest` | `concurrentCouponClaimsAndRollbackKeepOneEventPerReceipt` | 동시 쿠폰 수령은 회원 쿠폰과 발급 이벤트를 한 번만 기록하고 마지막 수량 소진도 유지한다 |
| `NotificationEventPostgresTest` | `unavailableBrokerRetainsEventAndRecoveryDeliversSameEnvelope` | Kafka 미도달 시 보류하고 복구 후 같은 eventId와 JSON을 실제 브로커에 보낸다 |
| `PaymentDelayReconcilerTest` | `confirmsOverdueSuccessfulPaymentAsPaid` | 지연된 INSTANT_SUCCESS 결제는 PAID로 확정한다 |
| `PaymentDelayReconcilerTest` | `unknownAttemptIsRecoveredAndLateUnknownCannotOverwriteConfirmedSuccess` | 미확정 결제를 복구하고 늦게 도착한 미확정 결과가 확정 성공을 덮지 못한다 |
| `PaymentDelayReconcilerTest` | `confirmsOverdueFailedPaymentAndRestoresStock` | 지연된 INSTANT_FAIL 결제는 FAILED로 확정하고 재고를 복구한다 |
| `PaymentDelayReconcilerTest` | `doesNotProcessPaymentTwiceAcrossReconciliationRuns` | 결제 복구를 두 번 실행해도 중복 처리하지 않는다 |
| `PaymentDelayReconcilerTest` | `excludesPaymentConfirmedAfterScanFromProcessedCount` | 스캔 후 다른 콜백이 확정한 결제는 처리 수에 포함하지 않는다 |
| `PaymentDelayReconcilerTest` | `failedApprovedGroupDoesNotBlockNextPaymentAndRemainsRetryable` | 승인 반영에 실패한 묶음은 다음 결제를 막지 않고 반영 재시도를 허용한다 |
| `PaymentDeliveryBoundaryPostgresTest` | `earlyResultBeforeHttpResponseIsAppliedOnce` | 결과 선도착은 requestId로 적용하고 늦은 PROCESSING HTTP 응답은 무시한다 |
| `PaymentDeliveryBoundaryPostgresTest` | `lostHttpResponseReusesDurableOriginal` | HTTP 응답 유실 후 같은 공개 키로 재요청하면 영속 requestId와 Payment 원본을 재사용한다 |
| `PaymentDeliveryBoundaryPostgresTest` | `refundReplayDoesNotDependOnRemotePaymentAvailability` | Payment가 불가용해도 같은 환불 키의 기존 접수 결과를 원격 조회 없이 반환한다 |
| `PaymentDeliveryBoundaryPostgresTest` | `refundRemoteLookupIsOutsideTransactionAndReferenceIsRevalidated` | 환불 원결제 HTTP 조회는 Core 트랜잭션 밖이며 저장 직전 변경된 원결제 참조를 거절한다 |
| `PaymentFlowIntegrationTest` | `confirmsInstantSuccessAndConsumesReservedStock` | INSTANT_SUCCESS 결제를 시작하면 PAID로 확정하고 예약 재고를 소진한다 |
| `PaymentFlowIntegrationTest` | `confirmsInstantFailureAndRestoresReservedStock` | INSTANT_FAIL 결제는 FAILED로 확정하고 예약 재고를 복구한다 |
| `PaymentFlowIntegrationTest` | `confirmsDelayedSuccessAfterDelay` | DELAYED_SUCCESS 결제는 지연 후 PAID로 확정한다 |
| `PaymentFlowIntegrationTest` | `usesPreconfiguredScenarioWithoutScenarioInBody` | 본문에 시나리오가 없어도 개발 레지스트리에 지정한 시나리오를 적용한다 |
| `PaymentFlowIntegrationTest` | `usesUpdatedTestRegistryScenario` | 테스트 레지스트리의 시나리오를 바꾸면 변경된 값을 사용한다 |
| `PaymentRecoveryPostgresTest` | `lostSuccessResponseKeepsReservationsAndExistingApprovalAppliesWithoutRetry` | 승인 응답이 유실되면 예약을 유지하고 재승인 없이 기존 승인을 반영한다 |
| `PaymentRecoveryPostgresTest` | `lostFailureResponseDoesNotReleaseUntilAuthoritativeFailureIsRead` | 실패 응답이 유실되면 확정 실패를 조회할 때까지 예약을 해제하지 않는다 |
| `PaymentRecoveryPostgresTest` | `fourFailedInvocationsExhaustBudgetAndRecreatedStoreOnlyQueries` | 실행 네 번이 실패하면 한도를 소진하고 저장소 재생성 후에는 조회만 수행한다 |
| `PaymentRecoveryPostgresTest` | `queryFailureAtExhaustionKeepsOneMinuteIntervalAndDoesNotExecute` | 한도 소진 후 조회에 실패해도 1분 간격을 유지하고 새 실행을 하지 않는다 |
| `PaymentRecoveryPostgresTest` | `approvalSurvivesBusinessRollbackAndDoesNotConsumeAnotherInvocation` | 업무 반영이 롤백되어도 승인을 보존하고 실행 횟수를 추가 소비하지 않는다 |
| `PaymentRecoveryPostgresTest` | `tokenExpiryAtFinalWriteRollsBackOrderStockCouponAndRetainsApproval` | 최종 저장 시 작업 토큰이 만료되면 주문·재고·쿠폰 반영을 롤백하고 승인은 보존한다 |
| `PaymentRecoveryPostgresTest` | `interruptedInvocationConsumesOneBudgetSlotBeforeProviderIsCalled` | 실행 중단은 제공자 호출 전에 실행 예산 한 번을 소비한다 |
| `PaymentRecoveryPostgresTest` | `scannerRecoversExpiredProcessingLeaseAndAppliesExistingApprovalOnlyOnce` | 스캐너는 만료된 처리 임대를 복구하고 저장된 승인을 한 번만 반영한다 |
| `PaymentRecoveryPostgresTest` | `scannerAtExhaustionQueriesWithoutNewAuthorizationAndSchedulesOneMinute` | 한도 소진 후 스캐너는 새 승인 없이 조회하고 다음 실행을 1분 뒤로 예약한다 |
| `PaymentResultKafkaPostgresTest` | `persistedPaymentAndRefundEnvelopesAreConsumedIdempotently` | 독립 Payment의 실제 결과 JSON을 Kafka로 받으면 주문·환불을 한 번만 반영한다 |
| `PaymentResultKafkaPostgresTest` | `kafkaResultBeforeHttpResponseIsAppliedOnce` | Kafka 결과가 HTTP 응답보다 먼저 도착해도 늦은 미확정 응답과 중복 결과를 한 번만 반영한다 |
| `PaymentResultKafkaPostgresTest` | `expiredApprovalKafkaAndHttpResultsRecordOneRequiredRefund` | (dev 병합 때 추가) 처리 만료 뒤 Kafka 승인 중복과 HTTP 결과는 환불 필요 한 건만 남기고 주문을 복구하지 않는다 |
| `PaymentSchedulingAfterCommitTest` | `confirmsInstantSuccessAfterDelayedCommit` | 커밋이 늦어져도 INSTANT_SUCCESS 결제를 확정한다 |
| `PaymentSchedulingAfterCommitTest` | `confirmsInstantFailureAndRestoresStockAfterDelayedCommit` | 커밋이 늦어져도 INSTANT_FAIL 결제를 확정하고 재고를 복구한다 |
| `PaymentSchedulingAfterCommitTest` | `doesNotSchedulePaymentWhenTransactionRollsBack` | 트랜잭션이 롤백되면 결제 처리를 예약하지 않는다 |
| `RefundDispatchDisabledPostgresTest` | `disabledCoreKeepsDurableRequestUntilEnabled` | Core 환불 비활성은 양수·0원 자동 전달을 막고 재활성 후 기존 원문과 결과 반영을 복구한다 |
| `RefundDispatchDisabledPostgresTest` | `disabledRefundBacklogDoesNotStarvePaymentDelivery` | 비활성 환불 100건이 앞서 있어도 후속 결제 재전송을 막지 않고 환불 원문을 보존한다 |
| `RefundDispatchDisabledPostgresTest` | `concurrentDeliveryPreservesPendingUntilPaymentLeaseCompletes` | (dev 병합 때 추가) 다른 전달이 Payment lease를 보유하면 미확정을 유지하고 확정 뒤 같은 원문으로 복구한다 |
| `RefundExecutionConcurrencyPostgresTest` | `concurrentExecutionOfOneRequestCreatesOneGatewayResult` | 환불 요청 하나를 동시에 실행해도 게이트웨이 결과 하나만 생성한다 |
| `RefundExecutionPostgresTest` | `successRefundsTargetOrderAndRestoresSoldStockOnce` | 환불 성공은 대상 주문을 환불 처리하고 판매 재고를 한 번만 복구한다 |
| `RefundExecutionPostgresTest` | `paymentGroupIsRefundedOnlyAfterEveryOrderIsRefunded` | 결제 묶음은 모든 주문이 환불된 뒤에만 환불 완료로 변경한다 |
| `RefundExecutionPostgresTest` | `persistedMoneySuccessIsAppliedAfterDatabaseFailureWithoutExecutingAgain` | 금전 처리 성공 후 DB 반영에 실패하면 재실행 없이 저장된 결과를 반영한다 |
| `RefundExecutionPostgresTest` | `successfulRefundDoesNotRestoreUsedCoupon` | 환불이 성공해도 이미 사용한 쿠폰은 복원하지 않는다 |
| `RefundExecutionPostgresTest` | `confirmedFailureIsTerminalAndDoesNotExecuteAgain` | 확정 환불 실패는 최종 상태이며 다시 실행하지 않는다 |
| `RefundExecutionPostgresTest` | `unknownWithoutGatewayResultCanBeRetriedUsingTheSameRequest` | 게이트웨이 결과가 없는 미확정 환불은 같은 요청으로 재시도할 수 있다 |
| `RefundExecutionPostgresTest` | `unknownAfterPersistedResultReusesItOnRetry` | 결과가 저장된 미확정 환불은 재시도 시 기존 결과를 재사용한다 |
| `RefundExecutionPostgresTest` | `cumulativeRefundCannotExceedThePaidGroupAmount` | 누적 환불액은 결제 묶음의 실제 결제액을 넘을 수 없다 |
| `RefundExecutionPostgresTest` | `zeroAmountRefundDoesNotCallTheMockGateway` | 0원 환불은 Mock 게이트웨이를 호출하지 않는다 |
| `RefundIntakeServicePostgresTest` | `selectedCartItemRefundUsesItsFullOrderAmountAndIdempotentReplay` | 선택한 장바구니 항목의 주문 전액을 환불하고 재요청은 기존 결과를 반환한다 |
| `RefundIntakeServicePostgresTest` | `emptyCartIdListMeansWholeGroupAndUnknownOrDuplicateIdsAreRejected` | 서비스의 빈 ID 목록은 전체 환불이며 알 수 없거나 중복된 ID는 거절한다 |
| `RefundIntakeServicePostgresTest` | `overlappingTargetsAreRejectedAcrossDifferentIdempotencyKeys` | 멱등 키가 달라도 대상 주문이 겹치는 환불은 거절한다 |
| `RefundIntakeServicePostgresTest` | `confirmedFailedRefundTargetCanBeRequestedAgainWithinRefundWindow` | 확정 실패한 환불 대상은 환불 기한 안에 다시 요청할 수 있다 |
| `RefundIntakeServicePostgresTest` | `refundWindowIsStrictlyLessThan168Hours` | 환불 접수는 결제 후 168시간 미만일 때만 허용한다 |
| `RefundIntakeServicePostgresTest` | `legacyProductWithoutSellerCanStillBeRefunded` | 판매자가 없는 기존 상품도 환불할 수 있다 |
| `RefundIntakeServicePostgresTest` | `concurrentSameMemberKeyAcrossGroupsReturnsConflictInsteadOfUniqueViolation` | 같은 회원 키로 여러 묶음에 동시 요청하면 유일성 오류 대신 충돌을 반환한다 |
| `RefundQueryPostgresTest` | `preservesListResponsesAndBoundsSql` | 구매자와 판매자의 1건·100건 목록은 기존 상세 응답과 같고 SQL 수를 제한한다 |
| `RefundQueryPostgresTest` | `preservesSellerPaginationCumulativeScopeAndIsolation` | 판매자 페이지 밖의 성공 환불도 누적하고 타 판매자의 금액과 대상은 숨긴다 |
| `RefundQueryPostgresTest` | `preservesEmptyListAndRecoveryResponse` | 환불이 없는 목록은 비어 있고 처리 중 복구 정보와 부분 환불 응답을 유지한다 |
| `RefundQueryPostgresTest` | `keepsCumulativeAmountsSeparateAcrossGroups` | 여러 결제 묶음을 한 페이지로 조회해도 묶음별 성공 누적액을 구분한다 |
| `RefundQueryPostgresTest` | `keepsRequestedTimeOrderAndIdTieBreaker` | 구매자 목록은 접수 시각 역순이며 시각이 같을 때 ID 역순을 유지한다 |
| `RefundReconcilerPostgresTest` | `persistedPendingRequestIsRecoveredWhenItsInMemoryCallbackIsMissing` | 메모리 콜백이 없어도 저장된 대기 환불을 복구한다 |
| `RefundReconcilerPostgresTest` | `twoReconciliationWorkersApplyTheSameRequestOnlyOnce` | 복구 작업자 두 명이 같은 환불을 처리해도 한 번만 반영한다 |
| `RefundReconcilerPostgresTest` | `expiredProcessingLeaseAndCommittedGatewayResultAreRecoveredAfterSevenDays` | 7일이 지나도 만료된 임대와 저장된 게이트웨이 결과를 복구한다 |
| `RefundReconcilerPostgresTest` | `exhaustedUnknownOnlyQueriesEachPersistedMinuteAndNeverAutomaticallyFails` | 한도가 소진된 미확정 환불은 1분마다 조회만 하며 자동 실패 처리하지 않는다 |
| `RefundReconcilerPostgresTest` | `lateResultAfterExhaustionIsAppliedByTheScannerWithoutSendingAnotherRefund` | 한도 소진 후 늦은 결과를 조회하면 추가 환불 없이 반영한다 |
| `RefundReconcilerPostgresTest` | `oneApplicationFailureDoesNotPreventAnotherRequestFromRecovering` | 한 요청의 반영 실패가 다른 요청의 복구를 막지 않는다 |
| `RefundReconcilerPostgresTest` | `exhaustedLookupFailureKeepsTheOneMinuteScheduleAndDoesNotSpin` | 한도 소진 후 조회 실패에도 1분 주기를 유지하고 즉시 반복하지 않는다 |
| `RefundReconcilerPostgresTest` | `applicationFailureAfterTheLastExecutionRetryAlsoSchedulesResultOnlyAfterOneMinute` | 마지막 PG 재시도 후 Core 반영이 실패해도 Payment 성공과 결과 재적용 경로를 보존한다 |
| `RefundRecoveryExecutionPostgresTest` | `initialCallPlusThreeRetriesExhaustsWithoutNewMoneyFailureOrStockRestore` | 최초 실행과 세 번의 재시도로 한도를 소진해도 새 금전 실패나 재고 복구를 만들지 않는다 |
| `RefundRecoveryExecutionPostgresTest` | `interruptedInitialProcessingConsumesOnlyTheNextRetryAndRecoversAfterSevenDays` | 최초 처리 중단 후에는 다음 재시도 한 번만 소비하며 7일 뒤에도 복구한다 |
| `RefundRecoveryExecutionPostgresTest` | `knownResultAfterExhaustionIsAppliedWithoutConsumingOrSendingAnotherExecution` | 한도 소진 후 확인된 결과는 새 실행 없이 반영한다 |
| `RefundRecoveryExecutionPostgresTest` | `staleWorkerCannotApplyMoneyResultOrRestoreStockAfterAnotherWorkerClaims` | 다른 작업자가 선점하면 이전 작업자는 금전 결과·재고 복구를 반영할 수 없다 |
| `RefundRecoveryExecutionPostgresTest` | `gatewayExecutionRunsOutsideTheBusinessTransactionAndGroupLock` | 게이트웨이 실행은 업무 트랜잭션과 결제 묶음 잠금 밖에서 수행한다 |
| `RefundRecoveryExecutionPostgresTest` | `confirmedFailureIsTerminalAndTheLeaseAndCounterAreNotReused` | 확정 실패는 최종 상태이며 임대와 실행 횟수를 재사용하지 않는다 |
| `RefundRecoveryExecutionPostgresTest` | `unexpectedDeliveryFailureAlsoStaysUnknownAndRetainsTheSameRequestBudget` | 예상하지 못한 전송 실패도 미확정 상태와 같은 요청의 실행 예산을 유지한다 |

### 일부만 지운 주문 테스트 (9개 파일)

결제와 무관한 단언은 살렸다. 옛 Payment DB를 지우던 정리 줄(`payments.deleteAll()`, `mock_gateway_result` 삭제, 시나리오 초기화)은 Core DB와 무관해 대체 없이 뺐다. 구분: 삭제 = 메서드 전체 삭제, 이름 변경 = 줄이면서 이름을 바꿈, 줄임 = 같은 이름으로 일부만 뺌, 경우 제거 = 파라미터 경우만 뺌.

| 파일 | 메서드(삭제 전 → 뒤) | 구분 | 빠진 것 | 표시 |
|---|---|---|---|---|
| `PaymentGroupIntegrationTest` | `onePaymentSuccessConsumesStockAndDeletesOnlyUneditedSelectedCartRows` | 삭제 | 결제 성공 시 재고 소진, 수정되지 않은 선택 장바구니만 삭제 | 복구할 회귀 |
| | `unconfirmedGatewayResultRetainsCouponCartAndStockUntilConfirmedOnce` | 삭제 | 미확정 게이트웨이 결과는 확정 전까지 쿠폰·장바구니·재고 유지, 확정은 한 번 | 복구할 회귀 |
| | `failedPaymentReturnsAllStockLeavesCartAndAllowsNewGroup` | 삭제 | 결제 실패 시 전체 재고 복구, 장바구니 유지, 새 묶음 허용 | 복구할 회귀 |
| | `gatewayApprovalSurvivesLocalApplyRollbackAndRetryUsesOneRecordedApproval` | 삭제 | 로컬 반영 롤백 뒤 재시도가 저장된 승인 하나를 사용 | 복구할 회귀 |
| | `concurrentStartAndCancelCommitExactlyOneTransition` | 삭제 | 결제 시작과 취소 동시 실행 시 전이 하나만 커밋 | 복구할 회귀 |
| | `concurrentResolveRecordsOneApprovalAndConsumesEveryProductOnce` | 삭제 | 결과 동시 반영 시 승인·재고 소진 한 번 | 복구할 회귀 |
| | `concurrentZeroPaymentStartsReturnOneCompletedAttempt` | 삭제 | 0원 결제 동시 시작 시 완료된 시도 하나(0원도 Payment가 확정한다) | 복구할 회귀 |
| | `zeroPaymentHttpResponseSignalsCompletionWithOk` | 삭제 | 0원 결제 HTTP 200 완료 응답 | 복구할 회귀 |
| | `fullyDiscountedGroupCompletesWithoutGatewayAndConsumesCouponStockAndCartOnce` → `fullyDiscountedGroupIsCreatedWithZeroPayableAndReservedCoupon` | 이름 변경 | 0원 확정(PAID)·쿠폰 USED·재고 소진·장바구니 삭제·재요청 같은 시도. 견적·0원 생성·쿠폰 예약은 되살림 | 복구할 회귀 · 되살림 |
| | `couponReservationIsReleasedOnCancellationExpiryAndDefinitivePaymentFailure` → `couponReservationIsReleasedOnCancellationAndExpiry` | 이름 변경 | 확정 결제 실패 시 쿠폰 해제. 취소·만료 해제는 되살림 | 복구할 회귀 · 되살림 |
| | `singleOrderCouponIsReservedSnapshottedAndConsumedAfterPaymentSuccess` → `singleOrderCouponIsReservedAndSnapshotted` | 이름 변경 | 결제 성공 뒤 쿠폰 USED·PAID·재고 소진. 할인 스냅샷·예약은 되살림 | 복구할 회귀 · 되살림 |
| | `singleOrderCouponReservationIsReleasedByCancellationExpiryAndDefinitiveFailure` → `singleOrderCouponReservationIsReleasedByCancellationAndExpiry` | 이름 변경 | 확정 결제 실패 시 해제. 취소·만료 해제와 재고 복구는 되살림 | 복구할 회귀 · 되살림 |
| | `singleOrderFullyDiscountedCouponCompletesWithoutGatewayAndConsumesCoupon` → `singleOrderFullyDiscountedCouponIsCreatedWithZeroPayable` | 이름 변경 | 0원 확정·쿠폰 USED·PAID. 0원 생성·예약은 되살림 | 복구할 회귀 · 되살림 |
| | `singleOrderCouponRemainsReservedWhilePaymentOutcomeIsUnresolved` | 줄임 | `DELAYED_SUCCESS` 시나리오 지정. 지금은 Payment에 닿지 않아 결과가 없는 상태로 미확정·쿠폰 예약 유지를 확인한다(되살림) | 복구할 회귀 · 되살림 |
| | `couponIsReservedWithDiscountSnapshotsAndIdempotentReplay` | 줄임 | 만료된 쿠폰도 결제 성공 시 USED. 할인 스냅샷·예약·재요청은 남김 | 복구할 회귀 |
| `SalesInfoEventIntegrationTest` | `failedPaymentRestoresStockButSuccessfulPaymentDoesNotChangePayload` | 삭제 | 결제 실패의 재고 복구 이벤트, 결제 성공은 payload 불변 | 복구할 회귀 |
| `CommerceCoreStateTransitionTest` | `rollsBackPaymentAndOrderConfirmationWhenStockUpdateFails` | 삭제 | 결제 결과 반영 중 재고 처리 실패 시 결제·주문 확정 롤백 | 복구할 회귀 |
| | `allowsNewOrderAfterRestoringLastReservedItem`, `preservesPrivateStatusWhenRestoringStock` | 경우 제거 | `fail`(결제 실패로 재고 복구) 경우. `cancel`·`expire`는 남김 | 복구할 회귀 |
| `CartOrderIntegrationTest` | `cartDoesNotReserveAndSuccessfulPaymentDeletesOnlySelectedItemWithReplay` → `cartDoesNotReserveAndReplayReturnsSameOrder` | 이름 변경 | 결제 성공 뒤 PAID와 선택 장바구니 항목 삭제. 재고 미예약·재전송 멱등·다른 금액 재사용 거절은 남김 | 복구할 회귀 |
| `MemberCommerceCoreApiTest` | `cartOrderPaymentAndHistoryUseSameMemberAndPreserveParentPaymentOwnership` → `cartOrderPaymentStartAndHistoryUseSameMemberAndPreserveParentPaymentOwnership` | 이름 변경 | 결제 완료(PAID) 대기, 결제 상세 SUCCESS, 결제 뒤 장바구니 0건, 같은 회원 두 번째 주문. 상위 결제 소유권 404(다른 회원의 주문으로)·이력(1건)·재고 7은 되살림 | 복구할 회귀 · 되살림 |
| `OrderCheckoutApiTest` | 없음 | 정리 줄만 | — | — |
| `LegacyOrderSellerSnapshotPostgresTest`(dev 병합 때 추가) | `preservesSellerAfterDeletion` | 삭제 | 구 API 주문의 상품 삭제 뒤 판매자 환불 조회(옛 Payment로 결제·환불 실행) | 복구할 회귀 |
| | `recoversLocalRestockAfterMoneyRefundSucceeded` | 삭제 | 환불 금전 성공 뒤 재고 상한 롤백과 같은 원문 수렴 | 복구할 회귀 |
| | `reservedOrdersSurviveProductClosure` → `reservedOrdersKeepDeadlineAndReservationAfterProductClosure` | 이름 변경 | 상품 중지·삭제 뒤 기존 주문 결제 성공(재고 소진). 신규 주문 차단·기한·선점 유지는 남김 | 복구할 회귀 |
| `OrderLegacySkuPostgresTest`(dev 병합 때 추가) | `legacySkuOnlyPaymentAndRefundRemainIdempotent` | 삭제 | Sales 이력 없는 SKU 주문의 결제 후 환불 수량 복구 한 번 | 복구할 회귀 |
| | `legacySuccessDoesNotRevalidateSale` | 삭제 | 판매 중지 뒤 기존 주문 결제 성공은 같은 SKU 예약만 소진 | 복구할 회귀 |
| `OrderSheetDomainPostgresTest`(dev 병합 때 추가) | `assertNoFinancialExecution`(도우미) | 줄임 | Payment DB `payment_attempt` 0건 단언. Core `payment_delivery_request` 0건은 남김 | 복구할 회귀 |

`MemberCommerceCoreApiTest`는 옛 Payment를 직접 import하지 않았지만, 기반 클래스의 fixture가 결제를 끝내 주는 데 기대고 있어 실행해 보고서야 찾았다.

남은 테스트(실행 수): `PaymentGroupIntegrationTest` 18, `SalesInfoEventIntegrationTest` 8, `CommerceCoreStateTransitionTest` 11, `CartOrderIntegrationTest` 6, `MemberCommerceCoreApiTest` 11, `OrderCheckoutApiTest` 12. PostgreSQL 테스트 수는 아래 검사 결과를 본다.

## PR 나누기

| PR | base | 브랜치 | 크기 |
|---|---|---|---|
| PR0 기존 코드 삭제 | `dev` | `refactor/#347-payment-legacy-removal` | XL 가능(삭제 줄). PR 본문에 이유 한 줄 |

PR0이 #347을 `Closes`한다.

## 미결정

결정(2026-11-15, 사용자 동의): 쓰지 않는 의존성 두 줄은 이 PR에서 지운다.

## 참고

[ADR 0337-2](../../../../../adr/0337-2-payment-replacement.md) · [#343 조율 댓글](https://github.com/2026-2-rising-sun/Backend/pull/343#issuecomment-6892067416) · [decision.md](../decision.md)
