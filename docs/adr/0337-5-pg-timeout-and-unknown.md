# ADR 0337-5: PG 결과를 모르면 UNKNOWN으로 두고 조회로 확정한다

결정 상태: 제안
구현 상태: [결제 요구사항](../requirements/payment.md)의 PAY-CONFIRM·PAY-PG·PAY-RECOVER
관련 자료: [응답 시간 예산](../exec-plans/active/337-payment-redesign/event-storming-v41.html#api) · [결과 불명](../exec-plans/active/337-payment-redesign/event-storming-v41.html#f4) · [토스 흐름](../exec-plans/active/337-payment-redesign/event-storming-v41.html#toss)

## 문제

토스 승인 응답이 없을 때 실패로 처리하면 돈은 빠졌는데 주문이 실패하고, 성공으로 처리하면 돈을 받지 않고 상품을 보낸다. 서비스 간 타임아웃 순서가 거꾸로면 위쪽 서비스에도 결과 불명 처리가 생긴다.

## 결정과 대안

- 연결 실패만 "보내지 않음", 나머지 애매한 실패는 UNKNOWN이다. UNKNOWN은 같은 멱등 키 재시도 1번 뒤 조회 스케줄러가 확정한다. 마감이 지나면 대사가 맡는다.
- 타임아웃은 위가 아래보다 길다: 토스 읽기 60초(토스 권장), Payment 승인 응답 대기 8초 뒤 202, Commerce→Payment 승인 10초, Gateway 15초. 결제 생성 호출은 3초.
- 토스 호출은 크기를 제한한 별도 스레드 풀에서 한다.
- 결제창마다 새 pg_order_id를 쓰고, 승인 멱등 키는 `confirm-{pg_order_id}`다. 거절 뒤 다른 카드로 다시 시도해도 첫 거절 응답이 돌아오지 않는다.
- 대안: 응답이 없으면 바로 취소(망취소). 정상 결제까지 잃어 택하지 않았다.

## 영향

202 비율을 지표로 보고 8초를 조정한다(10초 위로는 올리지 않는다). 조회 간격·마감(10초·30초·1분·5분, 30분)은 운영 후 조정한다.
