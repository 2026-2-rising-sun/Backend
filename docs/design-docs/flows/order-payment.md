# 주문·결제 서비스 간 흐름

<!-- visual: relations -->

새 주문서 경로는 결제창 준비와 실제 승인을 나눈다. 준비 동안 생성부터600초 PENDING을 유지하고 인증 후 승인 접수에서 PROCESSING 별도600초를 시작한다. 기존 공개 legacy 경로도 유지한다. Payment 실행·원장은 팀원 소유이며 Core는 주문·재고·쿠폰과 전달 기록을 소유한다.

기준: 2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`. 새 주문서 결제 HTTP 중계와 legacy 공개 입구가 함께 남아 있다. 기존 Mock 내부 결제·환불 API는 Payment에서 삭제됐지만 새 생성·승인·조회 어댑터는 별개다. 구 호출의 실패를 새 결제 경로 전체의 403으로 일반화하지 않는다. 늦은 승인·환불 요청 행과 [기존 경로와 이행](#기존-경로와-이행)은 dev `7bb41320` 코드로 다시 확인했다. 보상 대기와 환불 흐름은 [환불 흐름](refund.md)에서 설명한다.

## 새 계약 경로

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 사용자 | Core 주문서 | SKU/Cart·예상 금액 검증, 선점·판매자별 금액/설정 수수료·PREPARE 전달 원문 같은 TX |
| Core 커밋 후 | Payment 생성/checkout | 준비 데이터만 저장. PENDING/원래 기한 유지 |
| 사용자 인증 완료 | Core PROCESSING | 첫600초 처리기한·APPROVAL 원문 같은 TX. 반복 요청은 불연장 |
| Core 커밋 후 | Payment 조회/승인 | 먼저 조회, 유효한 미접수만 승인. 202/UNKNOWN·통신 예외는 실패가 아님 |
| Payment 확정 승인 | Core PAID | HTTP 조회 또는 PaymentApproved 검증·멱등 반영·재고 소진/쿠폰 사용 |
| Core 기한 경계 | EXPIRED | 재고/쿠폰 선점 한 번 해제. 이후 새 승인 POST 없이 조회 유지 |
| 종료 주문의 늦은 승인 | 시스템 보상 요청+Outbox | 승인 사실은 승인 HTTP 응답 뒤 원결제 조회가 기본이고, `payment.execution.results.v1`의 PaymentApproved가 보조 경로다. 주문 부활 없이 `core_refund_intent`(SYSTEM_COMPENSATION, 사유 LATE_APPROVAL)와 `commerce.refund.requests.v1`의 RefundRequested Outbox를 같은 TX에 저장 |
| Core 환불 요청 발행 | Payment 환불 소비 | Payment가 `commerce.refund.requests.v1`을 소비해 refundRequestId로 한 번만 접수하고 토스 취소를 실행한다. 발행 성공은 환불 완료가 아님 |
| Payment 환불 결과 | Core 환불 상태 | payment.refund.results.v1의 RefundResultRecorded를 core_refund_intent 원요청과 대조해 반영. 구 payment.refund-results.v1 구독은 #390에서 삭제됨 |

과거 실행 기록에서는 팀원 복구2c6aa250의 변경 없는 앱과 실제 Core/PostgreSQL/Kafka로 정상 승인, 처리 중 만료 뒤 늦은 승인, 실제8초202 뒤 Kafka만으로 PAID 반영을 확인했다. PG는 HTTP 대역이며 실 PG와 실제 환불 실행은 미실행이다. 이전 Mock/legacy 검증을 새 설계 E2E로 세지 않는다.

## 보상 대기와 재고 사본

승인 응답 유실·명시적 UNKNOWN 또는 종료 주문의 늦은 승인이면 주문을 COMPENSATION_PENDING(보상 대기)으로 바꾸고 `SYSTEM_COMPENSATION` 환불 요청을 보낸다. 보상 대기 동안 재고·쿠폰 예약은 보존하고, 검증된 완료 결과(SUCCEEDED 또는 NO_CHARGE)를 받아 CANCELLED로 확정할 때 한 번만 해제한다. 처리기한 만료의 EXPIRED 즉시 해제와 보상 완료 후 해제는 다른 경로다.

회원의 진행 중 주문 제한은 결제 대기(PENDING_PAYMENT)와 결제 확인 중(PAYMENT_CONFIRMING) 주문에만 걸린다. 보상 대기 주문이 있어도 같은 회원은 새 주문을 만들고 결제할 수 있다. 두 주문의 재고·쿠폰 예약은 서로 섞이지 않는다. 이 제한은 `PurchaseGuard.requireNoActive`, 부분 unique 인덱스(V117), `PaymentRelayService.active`가 함께 지킨다. 보상 대기 주문은 활성 주문 조회에 나오지 않는다. provider 부재·계약 불일치·paymentId 복구 차단이 남아 있다.

예약·소진·해제·환불 복원으로 바뀐 재고는 SKU dirty revision을 올리고 기본 60초 배치가 Catalog로 보낸다. Catalog 재고는 표시 정보이며 이 주문 처리의 원본 검증·잠금을 대신하지 않는다. [CDC 경로](catalog-sync.md)와 [최종 검증 범위](../../worklog/2026-11-20-cdc-refund-status.md)를 따른다.

## 기존 경로와 이행

정책0(legacy) 주문의 PAYMENT/REFUND 전달과 Payment 호출 경로는 #390에서 삭제됐다. 옛 주문 생성 API는 Frontend #89 전환 전까지 남지만 그 주문은 결제되지 않고 만료된다. 정책0 주문의 환불 요청은 409이고 환불 가능 조회는 `LEGACY_REFUND_UNAVAILABLE`을 돌려준다. 옛 `late_approval_refund`·`payment_relay_refund` 테이블과 기존 행은 지우지 않았다. 새 늦은 승인은 이 테이블에 쓰지 않고 `core_refund_intent`로 보상을 요청한다. 같은 주문에 옛 `payment_relay_refund` 행이 있으면 새 보상 요청을 만들지 않고 별도 복구 대상으로 남긴다. 과거 번호/기한/금액/기존 null 처리기한을 임의 재작성하지 않는다. 토스 테스트 환경의 주문 → 승인 → 부분·전체 환불 확인은 #367에 기록했다. 운영 결제·배포와 과거 미확정 거래의 운영 대사는 후속이다.

[Core 상세](../services/commercecore/order/overview.md) · [현재 원본·남은 기준](../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [실행 기록](../../worklog/2026-11-16.md) · [팀원 설계](../services/payment/overview.md)

## 이전 Mock 설명의 기준

기존 공통 설명은 `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사와 #330 문서 변경을 기준으로 했다. 당시 업무 코드나 실제 DB·HTTP·Kafka·PG 실행을 검증한 것으로 보지 않는다. 기존 Mock의 PAYMENT_CONFIRMING+PAYMENT 전달, Payment Mock 실행/결과 Outbox, Core의 재고·쿠폰 결과 반영은 legacy 경로의 설명이며 새 승인/환불 전체 통합의 증거로 합산하지 않는다.
