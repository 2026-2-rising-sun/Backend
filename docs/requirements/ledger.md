# 원장 수용 기준

상태: 초안
검증: [결제 재설계 실행 계획](../exec-plans/active/337-payment-redesign/README.md)

돈이 움직이는 상태 변경마다 복식 분개를 남긴다([ADR 0005](../adr/0005-ledger-double-entry.md)). 계정과 분개 규칙의 근거는 [이벤트 스토밍 v41](../exec-plans/active/337-payment-redesign/event-storming-v41.html#f9)이다. 정산·지급 분개는 해당 단계를 시작할 때 추가한다.

## 공통

- [ ] **LED-01** 분개 하나의 차변 합과 대변 합은 같다. 다르거나 줄이 없으면 아무것도 저장하지 않는다.
- [ ] **LED-02** 한 줄은 차변이나 대변 한쪽만 0보다 크다. 판매자 미지급금 줄에는 판매자 ID가 있다.
- [ ] **LED-03** 같은 사건(ref_type, ref_id)의 분개는 1번만 남는다. 두 번째 요청은 아무 일도 하지 않는다.
- [ ] **LED-04** 분개는 호출한 상태 변경과 같은 트랜잭션이다. 상태 변경이 롤백되면 분개도 없다.
- [ ] **LED-05** 분개는 고치거나 지우지 않는다. 정정은 반대 분개로 한다.

## 사건별 분개

- [ ] **LED-APPROVE-01** 결제 승인: 차변 PG 보관금(`PG_CUSTODY`) / 대변 구매자 예수금(`BUYER_DEPOSIT`), 결제 금액. 사건 키 (`PAYMENT_APPROVED`, payment.id).
- [ ] **LED-REFUND-01** 환불 성공: 차변 `BUYER_DEPOSIT` / 대변 `PG_CUSTODY`, 환불 금액. 사건 키 (`REFUND_SUCCEEDED`, refund.id). 구매확정 전후 모두 같다.
