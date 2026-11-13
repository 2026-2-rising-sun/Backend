# 환불 서비스 간 흐름

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](refund.html)

## 책임과 경계

Core 접수·전달과 Payment 실행의 토글을 구분한다. 실행 중/미확정/성공 환불을 누적 금액에 포함하고 중복 결과로 재고를 다시 복원하지 않는다.

## 전이

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 사용자 | Core 접수 | 소유권·기간·대상·누적 금액·멱등 키를 확인한다. |
| Core 요청 | Payment 실행 | 환불 전달 토글이 활성일 때 영속 원문을 전달한다. Payment 실행 토글은 별도로 적용한다. |
| Payment 확정 | Core 결과 반영 | 원결제·금액·대상과 결과 일치 확인 후 성공 대상 주문만 REFUNDED 및 재고 복원 |
| 부분 환불 | 묶음 PAID 유지 | 남은 자식 주문이 있으면 묶음 전체를 REFUNDED로 변경하지 않는다. |
| 모든 대상 완료 | 묶음 REFUNDED | 모든 자식 주문의 상태가 REFUNDED인 경우. |
| UNKNOWN | 결과 조회 유지 | 예산 소진·시간 초과만으로 확정 실패하지 않는다. |

## 상세와 근거

- [CommerceCore 환불 접수·결과 반영](services/commercecore/states/refund.md)
- [Payment 환불 실행](services/payment/states/refund.md)

[공통 Outbox](services/common-module/outbox.md) · [이벤트 계약](../../contracts/events)
