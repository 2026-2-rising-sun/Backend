# 주문·결제 서비스 간 흐름

<!-- visual: relations -->

새 주문서 경로는 결제창 준비와 실제 승인을 나눈다. 준비 동안 생성부터600초 PENDING을 유지하고 인증 후 승인 접수에서 PROCESSING 별도600초를 시작한다. 기존 공개 legacy 경로도 유지한다. Payment 실행·원장은 Payment 서비스가, 주문·재고·쿠폰과 전달 기록은 CommerceCore가 소유한다.

기준: 2026-11-27 dev `ba9be27c`(#416~#418 머지 뒤). 새 주문서 결제 HTTP 중계와 legacy 공개 입구가 함께 남아 있다. 기존 Mock 내부 결제·환불 API는 Payment에서 삭제됐지만 새 생성·승인·조회 어댑터는 별개다. 구 호출의 실패를 새 결제 경로 전체의 403으로 일반화하지 않는다. 보상 대기와 환불 흐름은 [환불 흐름](refund.md)에서 설명한다.

## 새 계약 경로

결과를 받는 주 경로는 Core 복구 루프의 1초 폴링(Payment HTTP 조회)이다. `payment.execution.results.v1`의 PaymentApproved는 보조 경로다.

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 사용자 | Core 주문서 | SKU/Cart·예상 금액 검증, 선점·판매자별 금액/설정 수수료·PREPARE 전달 원문 같은 TX |
| Core 커밋 후 | Payment 생성/checkout | 준비 데이터만 저장. PENDING/원래 기한 유지 |
| 사용자 인증 완료 | Core PROCESSING | 승인 시도마다 그 접수 시각부터600초 처리기한·APPROVAL 원문 같은 TX. 같은 시도의 반복 요청은 불연장. 시도 시작은 원래 생성 기한 전에만 된다 |
| Core 커밋 후 | Payment 조회/승인 | 먼저 조회, 유효한 미접수만 승인. 202·UNKNOWN·통신 예외는 실패가 아니다. 처리 기한까지 PAYMENT_CONFIRMING을 유지하고 1초마다 다시 조회한다(#416) |
| Payment 확정 승인 | Core PAID | 1초 폴링의 HTTP 조회 또는 PaymentApproved 검증·멱등 반영·재고 소진/쿠폰 사용 |
| 승인 거절(DECLINED) | Core PENDING_PAYMENT | 422 `DECLINED`. 예약을 유지하고 결제창(`pg_order_id`)·송신 표시를 비운다. 원래 만료 시각 전까지 같은 주문에서 새 결제창·새 `X-Idempotency-Key`로 재결제한다. PaymentFailed는 보내지 않는다(#418) |
| 202 응답 뒤 거절 | Core PENDING_PAYMENT | 1초 폴링이 Payment 결제를 CREATED이고 결제창이 이 시도와 다르다고 보면 위 거절과 같이 처리한다. 503 `PG_UNAVAILABLE`(토스에 가지 않음)과 UNKNOWN 뒤 토스 ABORTED·EXPIRED도 같은 판정으로 덮는다 |
| Payment CANCELED | Core FAILED | 보상이 없는 주문에서 Payment가 결제를 CANCELED로 닫은 경우다(승인 응답 불일치 전액 취소, UNKNOWN 조회에서 토스 전액 취소 확인, Payment 만료 배치 등). 재결제 대상이 아니다. 예약을 해제하고 PaymentFailed(`REJECTED`)를 보낸다 |
| 결제 대기 만료 | EXPIRED | PENDING_PAYMENT가 생성 기한에 닿으면 재고/쿠폰 선점을 한 번 해제한다 |
| 처리 기한 도달 | COMPENSATION_PENDING | PAYMENT_CONFIRMING이 600초 처리 기한에 닿으면 같은 TX에서 재고·쿠폰 예약을 해제하고(savepoint) 시스템 보상(사유 `APPROVAL_UNKNOWN`)을 요청한다(#417). 보상은 기한 판정만 만든다 |
| 보상 없이 닫힌 주문의 늦은 승인 | 시스템 보상 요청+Outbox | EXPIRED/CANCELLED/FAILED 주문에 승인 사실이 오면 주문 부활 없이 `core_refund_intent`(SYSTEM_COMPENSATION, 사유 LATE_APPROVAL)와 `commerce.refund.requests.v1`의 RefundRequested Outbox를 같은 TX에 저장한다. 이미 보상 중인 주문은 승인 사실만 남기고 기존 보상으로 수렴한다 |
| Core 환불 요청 발행 | Payment 환불 소비 | Payment가 `commerce.refund.requests.v1`을 소비해 refundRequestId로 한 번만 접수하고 토스 취소를 실행한다. 발행 성공은 환불 완료가 아님 |
| Payment 환불 결과 | Core 환불 상태 | payment.refund.results.v1의 RefundResultRecorded를 core_refund_intent 원요청과 대조해 반영. 구 payment.refund-results.v1 구독은 #390에서 삭제됨 |

과거 실행 기록에서는 Payment 복구 커밋 2c6aa250의 변경 없는 앱과 실제 Core/PostgreSQL/Kafka로 정상 승인, 처리 중 만료 뒤 늦은 승인, 실제8초202 뒤 Kafka만으로 PAID 반영을 확인했다. #416~#418은 Mock 토스(WireMock)와 실제 Core·Payment jar로 1차 77건 중 71건 통과(부분 3·실패 1·미검증 2)했고, 실패 1건은 고친 뒤 재검증했으며 최종 코드는 회귀 20건이 통과했다([기록](../../worklog/2026-11-27-core-payment-retry.md)). 실 PG와 실제 환불 실행은 미실행이다.

## 보상 대기와 재고 사본

처리 기한(승인 접수부터 600초)까지 결과를 모르면 주문을 COMPENSATION_PENDING(보상 대기)으로 바꾸고 `SYSTEM_COMPENSATION` 환불 요청을 보낸다. 명시적 UNKNOWN만으로는 보상하지 않고 기한까지 조회를 이어 간다. 보상 대기로 바꾸는 TX에서 재고·쿠폰 예약을 한 번 해제한다(`payment_group.compensation_released_at`, V118). 해제가 논리적으로 실패하면 JDBC savepoint까지만 되돌리고 보상 요청은 커밋한 뒤, 보상 완료(SUCCEEDED 또는 NO_CHARGE)로 CANCELLED가 될 때 다시 해제한다. 락 타임아웃·SQL 오류처럼 트랜잭션 밖으로 나간 예외는 보상 요청까지 롤백하고 다음 기한 판정에서 다시 시도한다. 보상 결과는 예약을 다시 해제하지 않는다. 보상 대기 주문은 되살아나지 않는다.

보상 행은 완료 뒤에도 정상 승인 재개를 막는 영속 fence다. 보상이 있는 주문은 승인 API 재진입, 결제창 열기, 전달 재송신, 구매자 취소, 만료 판정, 사용자 환불 접수를 모두 거절하거나 건너뛴다. 보상됐거나 닫힌 주문의 남은 승인 전달은 dispatch 때 `TIMEOUT`(`ORDER_CLOSED`)으로 닫아 복구 루프가 계속 집지 않게 한다.

회원의 진행 중 주문 제한은 결제 대기(PENDING_PAYMENT)와 결제 확인 중(PAYMENT_CONFIRMING) 주문에만 걸린다. 보상 대기 주문이 있어도 같은 회원은 새 주문을 만들고 결제할 수 있다. 두 주문의 재고·쿠폰 예약은 서로 섞이지 않는다. 이 제한은 `PurchaseGuard.requireNoActive`, 부분 unique 인덱스(V117), `PaymentRelayService.active`가 함께 지킨다. 보상 대기 주문은 활성 주문 조회에 나오지 않는다. provider 부재·계약 불일치·paymentId 복구 차단이 남아 있다.

예약·소진·해제·환불 복원으로 바뀐 재고는 SKU dirty revision을 올리고 기본 60초 배치가 Catalog로 보낸다. Catalog 재고는 표시 정보이며 이 주문 처리의 원본 검증·잠금을 대신하지 않는다. [CDC 경로](catalog-sync.md)와 [최종 검증 범위](../../worklog/2026-11-20-cdc-refund-status.md)를 따른다.

## 남은 구현 갭

| 갭 | 영향 | 처리 |
|---|---|---|
| 거절 뒤 PENDING_PAYMENT인 주문에 승인 사실이 도착 | Core가 "승인 결과를 적용할 주문 상태가 아닙니다" 충돌로 거부한다. Payment는 거절한 시도를 다시 승인하지 않으므로 정상 경로에서는 생기지 않는다 | 후속 |
| 보상이 토스 미승인 대기(156초) 뒤 NO_CHARGE로 끝난 다음 토스가 늦게 승인 | Payment가 감지하지 못한다 | 대사 또는 웹훅에서 처리. 후순위 |

## 기존 경로와 이행

정책0(legacy) 주문의 PAYMENT/REFUND 전달과 Payment 호출 경로는 #390에서 삭제됐다. 옛 주문 생성 API는 Frontend #89 전환 전까지 남지만 그 주문은 결제되지 않고 만료된다. 정책0 주문의 환불 요청은 409이고 환불 가능 조회는 `LEGACY_REFUND_UNAVAILABLE`을 돌려준다. 옛 `late_approval_refund`·`payment_relay_refund` 테이블과 기존 행은 지우지 않았다. 새 늦은 승인은 이 테이블에 쓰지 않고 `core_refund_intent`로 보상을 요청한다. 같은 주문에 옛 `payment_relay_refund` 행이 있으면 보상 요청 생성이 예외로 막혀 승인 반영 TX 전체가 롤백되므로 별도 복구가 필요하다. 과거 번호/기한/금액/기존 null 처리기한을 임의 재작성하지 않는다. 토스 테스트 환경의 주문 → 승인 → 부분·전체 환불 확인은 #367에 기록했다. 운영 결제·배포와 과거 미확정 거래의 운영 대사는 후속이다.

알려진 테스트 공백: 정책0 주문 환불 409, 옛 `payment_relay_refund` 행이 있을 때 늦은 승인 롤백은 단언하는 테스트가 없다.

[Core 상세](../services/commercecore/order/overview.md) · [현재 원본·남은 기준](../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [실행 기록](../../worklog/2026-11-16.md) · [Payment 설계](../services/payment/overview.md)

## 이전 Mock 설명의 기준

기존 공통 설명은 `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사와 #330 문서 변경을 기준으로 했다. 당시 업무 코드나 실제 DB·HTTP·Kafka·PG 실행을 검증한 것으로 보지 않는다. 기존 Mock의 PAYMENT_CONFIRMING+PAYMENT 전달, Payment Mock 실행/결과 Outbox, Core의 재고·쿠폰 결과 반영은 legacy 경로의 설명이며 새 승인/환불 전체 통합의 증거로 합산하지 않는다.
