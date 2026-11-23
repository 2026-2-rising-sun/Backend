# 같은 결제의 부분 환불 비차단 (#399, #345)

## 범위와 기준

- 기준: base `refactor/#402-refund-consumer`(2aea45c6, PR #411). 브랜치 `feat/#399-nonblocking-refund`.
- Payment 코드는 바꾸지 않았다. Core의 접수·결과 반영·계약 설명만 바꿨다.
- 기존 `CANCEL_PENDING` 행은 마이그레이션하지 않는다. 결과 반영이 기존 상태와 새 상태를 모두 처리한다.

## 결정

- 주문 항목·묶음 상태는 확정 사실만 가진다(`PAID`, `PARTIALLY_REFUNDED`, `REFUNDED`). 진행 상태는 `refund_request`가 가진다.
- 잔여 수량 = 구매 수량 - 성공 - PROCESSING - UNKNOWN. 금액 한도도 같은 기준의 잔액이다. 묶음 잠금 아래 계산한다.
- 쿠폰은 누적 전량 성공 때만 한 번 복원한다. 중복·역순 결과로 다시 복원되지 않는다.
- `refundable-targets`는 기존 `heldQuantity`, `refundableQuantity`, `remainingRefundableAmount`를 그대로 쓴다. 새 필드는 없다.

## 검증

| 명령 | 결과 |
|---|---|
| `commercecore-service:postgresTest --tests '*KafkaRefundPostgresTest'` (실패 테스트 선확인 후) | 실패 21/60 확인, 구현 후 61/61 통과 |
| `commercecore-service:postgresTest` 전체 | 204건 중 1건 실패: `ResultConsumerRewindKafkaPostgresTest`(#389). 단독 실행은 통과. 공유 EmbeddedKafka 컨텍스트에서 `PaymentRelayService` 스파이 호출 횟수가 누적되는 격리 문제로 보인다. 이 PR에서 고치지 않았다 |
| `scripts/check-docs.sh` | 문서 278개, 오류 0 |
| `render-api-docs.sh --check`, `render_design_docs.py --check` | 불일치 0 |

## 미실행·한계

- 전체 `build`의 `integrationTest`와 `scripts/check.sh --external`은 위 #389 테스트 때문에 통과하지 않았다.
- 실제 토스 환불은 호출하지 않았다. 외부 결제 환불 검증은 없다.
- 이전 정책 `CANCEL_PENDING` 행의 결과 반영은 테스트로 시뮬레이션했다(SUCCEEDED, FAILED).
