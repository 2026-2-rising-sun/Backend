# Core 주문·사용자 환불·시스템 보상

[API](api.md) · [ERD](erd.md) · [확정 상태표·최신 검증](../../../../worklog/2026-11-20-core-compensation-state.md) · [이전 실행 기록](../../../../worklog/2026-11-19-core-refunds.md)

## 기존 주문과 환불 경로

2026-11-20 기준 Core는 새 주문서의 결제 준비·승인을 HTTP로 중계했다. 사용자 환불은 기존 주문에서만 접수했고, 새 주문서가 닫힌 뒤 승인이 도착하면 `payment_relay_refund`에 요청을 저장하고 하이픈 토픽으로 발행했다. 당시 주문·결제 상태표와 PR343의 검증 범위는 [변경 전 주문 문서](https://github.com/2026-2-rising-sun/Backend/blob/9b807f91e02483c6ecf9b089ea42fa1f85b5f099/docs/design-docs/services/commercecore/order/overview.md)와 [11월 20일 검증 기록](../../../../worklog/2026-11-20-cdc-refund-status.md)에 있다.

[#378](https://github.com/2026-2-rising-sun/Backend/pull/378)은 신규 주문서의 사용자 환불과 시스템 보상 요청을 각각 저장하고 공통 Kafka 토픽으로 발행하는 Core 경로를 추가한다. 기존 주문과 이미 저장한 하이픈 토픽 요청의 결과는 기존 경로에서 처리한다.

## 주문과 승인

논리 주문은 `payment_group`, 구매 항목은 `orders`, 판매자별 금액 스냅샷은 `seller_order`다. 신규 주문서는 policyVersion=1이며 기존 policyVersion=0의 공개 주문·결제·환불 실행과 분리한다. SKU·상품명·판매자·단가·할인·수량·Cart ID/version은 생성 시 보존한다. 옵션은 기존 공급 범위대로 처리하며 환불 시 현재 상품 가격을 재계산하지 않는다.

회원 구매 guard → Cart 출처 → Product 공유 잠금 → SKU 쓰기 잠금 순으로 새 주문과 재고·쿠폰 예약을 원자적으로 만든다. Product 판매중지·삭제는 신규 주문을 막으며 이미 선점한 유효 주문의 결제까지 취소하지 않는다. 판매자별 양수 실결제액과 `commerce.commission-rate`를 생성 시 보존하며 설정 누락은 주문 전체를 롤백한다. legacy 0원 경로는 유지한다.

준비 PENDING_PAYMENT는 생성부터600초, 승인 진입 PAYMENT_CONFIRMING은 최초 전환부터 별도600초다. PREPARE/APPROVAL 원문·고정 전달ID와 상태는 같은 Core TX에 저장하고 승인 HTTP는 커밋 뒤 수행한다. 정상202 PENDING은 처리 중 응답이며 보상 의도를 만들지 않는다. 단순 조회 실패도 보상 접수 근거가 아니다.

확정 승인은 구매 guard와 그룹 잠금 아래 원결제ID·금액·승인시각을 검증한다. 유효 주문은 PAID와 판매 수량 소비·쿠폰 사용·본인 구매 SKU Cart 정리를 같은 TX에 반영한다. Cart 출처 ID/version은 삭제 뒤에도 보존한다. 실패·취소·만료·보상 대상 늦은 승인에서는 Cart를 정리하지 않는다.

## #340 Cart 출처와 성공 정리

Cart 출처 ID/version은 주문 생성 시 선택 검증과 기존 선택 환불 계약의 식별 자료로 보존한다. 성공 삭제에는 회원 ID + 해당 주문항목의 SKU만 사용하므로 결제 중 수량 변경·삭제 후 재추가도 정리한다. 같은 상품의 다른 SKU·다른 회원·직접 구매의 Cart는 보존한다. 기존 terminal 결과/승인 사실 및 상태 전이로 최초 성공만 반영하며 중복 성공 이후 재담기는 보존한다. 실패·취소·만료·늦은 승인에서는 Cart를 정리하지 않는다. Cart→SKU 잠금 순서와 상태·재고·쿠폰·알림의 원자성은 유지한다. [Cart 결정](../cart/overview.md#2026-11-18-340-성공-정리-결정).

## 시스템 응답 유실과 보상 fence

최초 승인 처리 시작부터600초 무응답 또는 명시적 승인 UNKNOWN에서 `core_refund_intent(purpose=SYSTEM_COMPENSATION,status=REQUESTED)`와 전체 요청 원문·고정UUID·`commerce.refund.requests.v1` Outbox를 같은 TX에 저장한다. 사용자 CANCEL_PENDING으로 바꾸지 않는다. 그룹과 관련 항목을 COMPENSATION_PENDING으로 바꾸고 재고·쿠폰 예약을 유지한다. 단순 HTTP 통신오류·timeout·응답 유실은 즉시 보상하지 않고 기존 PROCESSING에서 첫 처리 시작의600초 기한까지 기다린다. 재시도로 기한을 늘리지 않는다.

보상 행은 완료 뒤에도 정상 승인 재개를 차단하는 영속 fence다. 승인 API 재진입, checkout, dispatcher 재전달과 만료/취소는 이를 확인한다. 송신 직전에는 같은 그룹 잠금 아래 `approval_send_started_at`을 기록하는 조건부 송신 시작 TX를 커밋한다. 이 지점보다 보상이 먼저면 HTTP를 보내지 않으며, 시작권한 커밋 뒤의 호출은 이미 진행 중인 호출로 분류한다. DB 잠금을 HTTP 동안 유지하지 않는다. 이미 시작된 HTTP와 보상 접수의 경합은 늦은 승인 사실을 저장하되 기존 요청ID의 보상으로 수렴시킨다. 새 `late-*` 요청을 만들거나 정상 PAID로 부활시키지 않는다.

결제 전 PENDING_PAYMENT는 기존 기한에 EXPIRED로 만료·해제한다. PAYMENT_CONFIRMING의600초 기한 도달은 보상대기 진입이며 일반 만료·선해제가 아니다. 보상 REQUESTED/UNKNOWN/FAILED인 진행 주문은 만료 배치에서 예약을 해제하지 않는다. 승인 반영·보상 접수·만료는 guard → 그룹 잠금 순서로 직렬화한다. 승인 사실이 먼저 PAID를 확정하면 뒤 늦은 UNKNOWN 콜백은 보상을 새로 만들지 않는다.

| 보상 결과 | 진행 주문 | 이미 EXPIRED/CANCELLED/FAILED인 주문 |
|---|---|---|
| UNKNOWN | COMPENSATION_PENDING·예약 유지, 보상 UNKNOWN | 종료 상태 유지 |
| FAILED | COMPENSATION_PENDING·예약 유지, 원인 기록. 같은 요청·실행의 더 높은 버전 완료 결과 재검증 가능 | 종료 상태 유지 |
| SUCCEEDED/NO_CHARGE | CANCELLED, 예약 재고·쿠폰을 한 번 해제. 시스템 완료 알림은 발행하지 않음 | 종료 유지, 보상 COMPLETED만 기록, 추가 자원 반환 없음 |

NO_CHARGE는 Payment가 미승인과 향후 승인 불가를 확정한 결과다. 일시 not-found·조회 장애를 Core가 NO_CHARGE로 추정하지 않는다. 금전 승인 사실과 Core의 CANCELLED 주문 결과는 서로 다른 축이다. 장기 UNKNOWN/FAILED의 자동 해제나 임의 TTL은 없다. 완료 이후 낮은 버전 UNKNOWN/FAILED 재전달은 추가 자원 효과가 없고, 더 높은 버전의 모순 종료 결과는 거절한다. 이미 종료한 과거 주문은 보상으로 재활성화하지 않는다.

## 사용자 전체·선택·부분수량 환불

`POST /v1/payment-groups/{number}/refunds`는 기존 API 토글을 유지한다. 본문 생략은 잔여 전량, `items[{orderId,quantity?}]`는 선택 항목·부분수량, `cartItemIds`는 저장 출처와 유일하게 연결된 항목의 잔여 전량이다. 직접 구매도 orderId로 선택한다. 두 방식 혼용·중복ID·빈/null 배열·소수·알 수 없는 필드는400, 예상금액 불일치·수량/금액 초과·다른 미완료 요청·보상 fence는409다.

신규 접수는 소유권·PAID/PARTIALLY_REFUNDED·저장 승인시각부터168시간 미만·잔여 수량·실결제액을 그룹 잠금 아래 확인한다. 같은 결제의 PROCESSING/UNKNOWN은 하나만 허용한다. 미확정 병렬 부분환불의 금액 재배분 정책을 새로 도입하지 않는다.

금액은 `min(단가×이번수량, 항목실결제액−성공환불누계)`다. 4,000×3−쿠폰2,000의 순차1개 환불은4,000/4,000/2,000이다. 원문·이전 항목 상태·요청수량·금액을 `refund_request/refund_target_order`에 저장하고 그룹과 대상 항목만 CANCEL_PENDING으로 바꾸며 공통 intent/Outbox도 같은 TX에 저장한다. 비대상 항목은 유지한다.

회원+Idempotency-Key는 묶음·입력방식·정렬된ID·명시/생략 수량·expectedAmount로 고정한다. 같은 원문 재조회는 상태/기간 검사 전에 처리하며 남은 수량을 다시 확장하지 않는다. 같은 키 다른 원문은409다. 접수201은 금전 성공이 아니다.

UNKNOWN은 수량·금액 예약과 CANCEL_PENDING을 유지한다. SUCCEEDED만 이번 수량을 restock하고 누적 수량으로 항목·그룹의 PARTIALLY_REFUNDED/REFUNDED를 계산한다. 쿠폰은 전수량 성공 시 `used_payment_group_id`를 대조해 원 만료를 유지하여 한 번 복원한다. 확정 FAILED는 저장한 이전 항목 상태와 성공 누계에 따른 그룹 상태로 돌아가며 재고·쿠폰을 변경하지 않는다.

## 결과 검증과 전달

요청/결과 토픽은 `commerce.refund.requests.v1` / `payment.refund.results.v1`, 승인 사실은 `payment.execution.results.v1`이다. 기존 하이픈 승인·환불 결과 listener는 저장된 legacy 원문 처리에 보존한다. Notification/Live와 기존 `commerce.payment-group.v1` 알림은 유지한다.

결과는 source/version/eventId/key/orderRef·고정 요청ID(영숫자·밑줄·하이픈1~64자)·paymentId·목적·사유·통화·요청시각·금액·판매자/항목/수량을 원문과 대조한다. executionId는 고정하며 resultVersion의 동일버전 다른 본문·동일eventId 다른 본문·모순 종료 결과를 거절한다. 낮은 UNKNOWN과 같은 종료 의미 재전달은 자원 효과0회다. 미등록·불일치 결과나 DB 실패는 예외로 남겨 listener가 무한 backoff 재전달하며 성공 ack하지 않는다. 별도 격리/DLQ 운영 화면은 이번 Core 구현 범위 밖이다.

사용자 환불의 결과 반영·재고/쿠폰·주문·요청/결과 원장·기존 환불 알림 Outbox는 같은 Core TX다. 시스템 완료는 원문·COMPLETED/outcome·COMPENSATION_PENDING→CANCELLED와 예약 해제만 같은 TX에 저장하며 PG 거절을 뜻하는 PaymentFailed나 계약에 없는 OrderFailed를 합성하지 않는다. 정상 결제 거절·실제 타임아웃 알림은 기존 경로대로 유지한다. 별도 시스템 보상 완료 알림은 후속 범위다. Payment의 실제 PG 실행과 Core 로컬 반영은 별도 TX이며 Core rollback이 이미 실행된 금전 환불을 취소하지 않는다.

## 호환·설정·Frontend 영향

policyVersion=0의 과거 키·금액·미완료 HTTP 원문과 `late_approval_refund/payment_relay_refund`는 자동 재작성·Kafka 재발행하지 않는다. legacy 부분수량은 완료 범위가 아니다. 신규 Kafka 접수는 `execution-enabled`와 무관하게 Outbox를 저장하며 이 토글은 legacy HTTP에만 적용한다. 공용 Outbox relay가 OFF이면 전달은 지연되고 접수는 보존된다. 기본 application.yml과 SKU 내부 추적은 이번 환불 변경에 포함하지 않는다.

Frontend는 새 주문/항목 COMPENSATION_PENDING, CANCEL_PENDING과 PARTIALLY_REFUNDED, `payment.compensation{requestId,status,outcome,completedAt}`, `refundRequestId`, 요청 targets.quantity와 환불 가능 누계 필드를 처리해야 한다. UNKNOWN이나201/202를 성공으로 표시하지 않아야 한다. Frontend 파일은 수정하지 않았다.

V115는 신규 intent/결과event 원장·이전 항목 상태·CANCEL_PENDING CHECK를 추가한다. 기존 PK·금액·Cart 출처와 V104~V106은 보존한다. 이미 다른 과거 migration을 적용한 운영 환경의 이행·배포를 검증했다고 주장하지 않는다. 검증 수치·실패·중단·Kafka/PG 미실행은 최신 워크로그에서 확인한다.

## 새 Payment와 기존 Mock의 계약 구분

| 경계 | 새 주문서 | 기존 legacy |
|---|---|---|
| 준비·승인 | `/v1/internal/payments`·orderRef·실제 결제창 pgOrderId·고정 HTTP 원문 | policyVersion0의 PaymentClient/PaymentCommand와 숫자 paymentId |
| 환불 | 고정 요청ID·purpose·판매자/항목/수량을 가진 Kafka 원문·Core가 기대하는 RefundResultRecorded | 기존 내부 HTTP 원문과 RefundExecutionResolved·late 전용 구 결과 |
| 주소 | `commercecore.payment-relay.base-url`, 미지정 시 기존 client base-url fallback | `commercecore.payment-client.base-url` |

동일 이름 필드만으로 구 Mock과 새 Payment가 호환된다고 가정하지 않는다. 서비스 토큰은 기존 Payment client 설정과 실제 서버를 맞춘다. 실제 Payment/PG 연결 증거는 Core 대역검사와 구분한다.

## 9. 직접 합의와 구분하는 구현 선택

회원당 활성1건·168시간 환불·단가 우선 환불과 전수량 쿠폰 복원은 기존 정책을 유지한다. 이번 변경은 같은 결제 미완료 환불을 직렬화하며 미확정 병렬 금액 재배분을 새로 결정하지 않는다. Cart 담기 판매조건·0재고 판매시작·공개 가격순·옵션의 미결정 범위를 추가 구현하지 않는다. 실제 공급자·운영 배포·과거 migration 변형 환경의 수용은 로컬 검사 통과와 별개다.

## 현재 검증 경계

환불 공통 요청은 `commerce.refund.requests.v1`, 공통 결과는 `payment.refund.results.v1`이며 두 목적 모두 처음부터 존재하는 orderRef(groupNumber)를 key로 쓴다. Core는 저장된 refundRequestId·목적·원문으로 결과를 검증한다. Payment의 consumer·조회/대사·PG 환불·결과 producer는 타팀 책임으로 이번 구현·실행 범위에 없다. 결과 테스트는 Core fixture 주입이다. 기존 원문에 paymentId가 없고 결과에만 새 ID가 있는 경우에는 검증된 기존 binding이 없으면 적용을 보류하고 재전달한다. 현재 Payment GET 계약의 필드로 안전한 연결 복구를 증명하지 못했으므로 미해결이며 새 필드를 가정하거나 검증을 완화하지 않는다.

V116은 기적용 V115를 변경하지 않고 상태 CHECK와 활성 회원 인덱스를 확장한다. 기존 미완료 시스템 보상과 진행 주문만 새 대기 상태로 이행하고 원문·요청ID·예약과 이미 종료한 주문은 보존한다.

Core 선병합 기준(`dev` `1dbedd4a`)에서 Payment 생산 계약은 구 `payment.refund-results.v1/RefundExecutionResolved`이며 신규 dot 토픽 `payment.refund.results.v1/RefundResultRecorded`와 불일치한다. 새 결과 모양은 Core 테스트 fixture이고 상대 producer 확정·연동 완료를 뜻하지 않는다. 이 계약 게이트가 해소되기 전 실제 환불 연동 완료로 배포하지 않는다.

Payment의 [결과 계약 #393](https://github.com/2026-2-rising-sun/Backend/pull/393), [요청 소비·결과 발행 #394](https://github.com/2026-2-rising-sun/Backend/pull/394), [사용자 환불 #395](https://github.com/2026-2-rising-sun/Backend/pull/395), [시스템 보상 #396](https://github.com/2026-2-rising-sun/Backend/pull/396)이 후속 변경이다. Core가 먼저 구·신 승인 토픽을 모두 구독하면 Payment가 승인 발행 토픽을 전환할 수 있다.
