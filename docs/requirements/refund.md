# 결제 환불 수용 기준

상태: 초안
검증: [결제 재설계 실행 계획](../exec-plans/active/337-payment-redesign/README.md)

Payment가 승인된 환불을 실행하는 기준이다. 환불해도 되는지는 Commerce가 정한다. 근거: [Payment 목표 설계](../design-docs/services/payment/design.html) `#f7`, `#f6`, `#api`, `#contract`. 현재 구현 설명은 [환불 설계](../design-docs/refund.md)에 있고, 교체 PR에서 함께 정리한다.

이 문서는 환불 구현을 시작할 때 다시 다듬는다.

## 접수

- [ ] **REF-ACCEPT-01** 접수하면 PROCESSING 환불을 저장하고 바로 202 `{refundId, status}`를 돌려준다. 토스 호출은 뒤에서 한다.
- [ ] **REF-ACCEPT-02** 같은 refundRequestId·같은 본문은 같은 환불(200), 다른 본문은 409 `REFUND_REQUEST_CONFLICT`. (EC 39)
- [ ] **REF-ACCEPT-03** 동시 부분 환불의 합이 결제 금액을 넘으면 한 건만 409 `OVER_REFUND`. (EC 40)
- [ ] **REF-ACCEPT-04** 부분 취소가 안 되는 결제의 부분 환불은 409 `PARTIAL_NOT_ALLOWED`. (EC 43)
- [ ] **REF-ACCEPT-05** items가 없으면 400 `ITEMS_REQUIRED`, 이 결제에 없는 판매자 주문이면 400 `UNKNOWN_SELLER_ORDER`, 결제가 없으면 404 `PAYMENT_NOT_FOUND`, 환불할 수 없는 상태면 409 `NOT_REFUNDABLE`.

## 실행

- [ ] **REF-EXEC-01** 접수 커밋 직후 실행한다. 끝나지 못한 PROCESSING 환불은 스케줄러가 다시 집는다. 두 실행이 겹쳐도 멱등 키(refund_request_id)로 이중 환불이 없다. (EC 41)
- [ ] **REF-EXEC-02** 성공하면 한 트랜잭션에서 환불 SUCCEEDED, 결제 환불액·상태(PARTIALLY_REFUNDED/REFUNDED), 분개([LED-REFUND-01](ledger.md)), `RefundSucceeded` 이벤트를 기록한다.
- [ ] **REF-EXEC-03** 토스가 거절(4xx)하면 FAILED와 `RefundFailed` 이벤트. (EC 42)
- [ ] **REF-EXEC-04** 결과를 모르면 UNKNOWN. 결제 조회로 취소 내역을 확인하고, 없으면 같은 멱등 키로 다시 요청한다. 조회 마감이 지나면 멈추고 대사가 맡는다.

## 늦은 승인 환불

- [ ] **REF-LATE-01** `RefundRequested`(사유 LATE_APPROVAL, refundRequestId `late-{paymentId}`)를 받으면 결제 전액 환불을 접수한다. 중복 수신은 1건이다. (EC 28, 37)
- [ ] **REF-LATE-02** 소비 중 기술 오류는 재시도 뒤 DLT로 보낸다. 업무 실패는 이벤트로 알린다.

## 결과 이벤트

- [ ] **REF-EVENT-01** `RefundSucceeded`·`RefundFailed`를 `payment.refund-results.v1`에 orderRef 키로 보낸다. 계약 YAML에 예시와 발행 표본이 있다.
