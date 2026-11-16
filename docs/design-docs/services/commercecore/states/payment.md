# CommerceCore 결제 전달·결과 반영

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](payment.html)

## 범위와 불변조건

Core의 payment_delivery_request는 실행 원본이 아니라 영속 전달·결과 사본이다. 커밋 후 전달하며 복구 스케줄러가 같은 원문을 재전달한다. terminal 이후 늦은 PROCESSING/UNKNOWN으로 덮어쓰지 않는다. 전달 생성/재시도 시각과 due 조회는 같은 DB시각을 사용해 JVM 시각 차이로 새 요청이 미래로 보이는 일을 막는다. PENDING·TIMEOUT 호환 값은 신규 흐름에 그리지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 주문을 확인 중으로 바꾸는 트랜잭션에서 request_id와 전달 원문을 저장한다. |
| PROCESSING | UNKNOWN | Payment가 유효한 미확정 결과를 반환하면 저장한다. 전송 예외만으로 확정 실패를 만들지 않는다. |
| UNKNOWN | PROCESSING | Payment가 원문과 일치하는 PROCESSING 응답을 반환하면 미확정 스냅샷을 갱신할 수 있다. |
| PROCESSING | SUCCESS | requestId·결제/주문/묶음 ID·금액·시각을 검증한 확정 결과를 주문 처리와 같은 트랜잭션에 기록한다. |
| UNKNOWN | SUCCESS | 재전달·조회·이벤트로 확정 결과를 확인하면 기한 내 주문 성공 또는 기한 후 환불 필요 사실과 함께 반영한다. |
| PROCESSING | FAILED | 동일 원문의 확정 실패만 반영한다. |
| UNKNOWN | FAILED | 조회로 확정 실패를 확인한 경우. 단순 통신 시간 초과와 구분한다. |

## 구현과 검증 근거

- [PaymentDeliveryStore.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryStore.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [OrderPaymentOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderPaymentOutcomeService.java)

관련 기존 테스트: [PaymentResultHandlerTest.java](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandlerTest.java) · [PaymentDeliveryBoundaryPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryBoundaryPostgresTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

## 2026-11-16 현재 PR 경계

새 공개 주문 생성은 기본600초이며 첫 승인 실행 접수에서 별도600초 기한을 저장한다. 처리 기한 도달은 EXPIRED와 선점해제이며 Payment 실패 확정이 아니다. legacy 늦은 승인은 REQUIRED 기록이며 새 계약 경로는 환불 요청 outbox와 결과 소비를 제공한다. 실제 Payment 환불 통합은 미완이다. 기존 공개 결제/환불 입구를 제거하거나503으로 막지 않는다. [현재 작업](../../../../exec-plans/active/331-order-audit-fixes/processing-deadline.md) · [새 검증](../../../../worklog/2026-11-16.md). 전체설계 미충족·Draft·배포 불가다.

## 팀원 계약 중계의 현재 상태

새 `/v1/order-sheets` 준비는 PENDING 유지, 인증 후 승인 접수만 PROCESSING과 별도600초를 시작한다. PREPARE/APPROVAL 전달 원문과 상태는 같은 TX이며 HTTP는 커밋 후다. 202/UNKNOWN은 미확정, 기한 경계는 EXPIRED와 선점 해제다. 늦은 승인→payment_relay_refund REQUESTED와 RefundRequested outbox→결과 소비 SUCCEEDED/FAILED다. 주문은 종료 상태를 유지한다. legacy REQUIRED 기록과 새 원결제 환불표는 분리한다.

Core PostgreSQL/계약 대역·Kafka 검사와 실제 Payment 생성·조회·checkout 확인을 구분한다. 실제 환불 생산자·소비자는 아직 발견하지 못해 전체 통합은 미완이다. [현재 원본·남은 기준](../../../../exec-plans/active/331-order-audit-fixes/processing-deadline.md).

## 새 Payment와 기존 Mock의 계약 구분

2026-11-17 Core `ebea69d6`와 팀원 `feat/#349-payment-recovery`의 `800b41fd`를 대조했다. 새 주문서의 요청은 이미 팀원의 새 DTO와 일치한다. dev에 남아 있는 기존 `PaymentCommand`는 별도 실행 계약이므로 필드 이름만 바꾸거나 새 주문서를 그 DTO로 되돌리지 않는다.

| 단계 | Core 요청 | 팀원 새 Payment 수신 |
|---|---|---|
| 생성 POST `/v1/internal/payments` | `Create`: orderRef, buyerId, amount, expiresAt, sellerAmounts | `CreatePaymentRequest`. 구매자·판매자 ID는 UUID, 시각은 오프셋 포함 ISO 문자열, 판매자별 금액과 수수료를 받는다. 준비만 하며 PG 승인을 실행하지 않는다. |
| 결제창 POST `/{orderRef}/checkouts` | 본문 없음 | 새 pgOrderId, amount, expiresAt 반환 |
| 조회 GET `/{orderRef}` | 문자열 주문 참조 | ApiResponse data의 상태·금액·환불액·승인 시각 반환 |
| 승인 POST `/{orderRef}/approval` | `Approval`: buyerId, paymentKey, pgOrderId, amount | `ApprovalRequest`. 200 APPROVED, 202 PENDING과 Location, 422 DECLINED를 구분한다. |
| 기존 Mock 실행 | `PaymentCommand`: requestId, paymentId, orderId, paymentGroupId, referenceNumber, amount, scenario | 생성 준비가 아니라 기존 결제 실행이다. 조회도 숫자 paymentId이며 응답 봉투가 다르다. |

새 Payment 연결 주소는 `commercecore.payment-relay.base-url`로 명시한다. 기존 Mock을 함께 검증할 때는 `commercecore.payment-client.base-url`과 서로 다른 주소를 사용한다. relay 주소를 생략하면 기존 client 주소(그것도 없으면 localhost:8086)를 사용하므로 구형 Mock 앱을 새 Payment로 오인하지 않는다. 서비스 토큰은 `commercecore.payment-client.service-token` 또는 `COMMERCECORE_PAYMENT_SERVICE_TOKEN`을 사용하며 실제 Payment 설정과 일치해야 한다.

팀원 새 Payment는 PR #352–#359의 별도 작업이다. 이 문서·회귀 테스트의 병합이 해당 구현의 dev 병합이나 배포를 뜻하지 않는다. [계약 회귀 테스트](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/payment/relay/HttpPaymentRelayClientTest.java)와 [이번 검증 범위](../../../../worklog/2026-11-17-payment-contract.md)를 구분해 확인한다. 취소 요청 #360과 신규 주문서 일반 환불은 별도다.
