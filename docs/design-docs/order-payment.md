# 주문·결제 서비스 간 흐름

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](order-payment.html)

## 책임과 경계

실행 책임은 Payment, 주문·재고·쿠폰 정책은 Core다. 0원도 영속 실행·결과 기록을 남긴다. 운영 PG 연동 검증과 Mock 처리를 구분한다.

## 전이

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 사용자 | Core 주문 | 본인·가격·수량·멱등 키 확인 → 재고·쿠폰 예약과 주문/묶음 저장 |
| Core 확인 중 | Core 영속 전달 | PAYMENT_CONFIRMING과 payment_delivery_request를 같은 트랜잭션에 저장 |
| Core 커밋 후 | Payment 접수 | 같은 requestId·원문을 HTTP로 전달한다. 응답 유실 시 같은 요청을 재전달한다. |
| Payment 실행 | Payment 결과·Outbox | Mock 제공자 실행/조회·lease 확인 후 원본과 결과 이벤트를 함께 저장 |
| Payment 결과 | Core 반영 | 이벤트·HTTP 결과를 같은 검증 경로에서 멱등 반영. SUCCESS는 재고 소비, FAILED는 예약 복원 |
| Payment UNKNOWN | Core 확인 중 유지 | 통신 시간 초과만으로 재고·쿠폰을 풀지 않는다. 확정 결과 조회를 계속한다. |

## 상세와 근거

- [CommerceCore 주문·결제 묶음](services/commercecore/states/order.md)
- [CommerceCore 결제 전달·결과 반영](services/commercecore/states/payment.md)
- [Payment 결제 실행](services/payment/states/payment.md)

[공통 Outbox](services/common-module/outbox.md) · [이벤트 계약](../../contracts/events)
