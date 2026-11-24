# 멈춘 고객 환불 수동 처리

상태: 진행 중
검증: [환불 수용 기준](../requirements/refund.md) `REF-EXEC-04`·`REF-EXEC-06`, [결제 설계](../design.md), [#369](https://github.com/2026-2-rising-sun/Backend/issues/369)

자동 복구는 고객 환불을 첫 시도부터 24시간까지 다시 시도한다. 그 뒤에도 결과를 확정하지 못한 환불은 UNKNOWN에 `next_attempt_at` NULL로 멈추고 ERROR 로그 `[사람 확인 필요]`를 한 번 남긴다. 이 문서는 멈춘 건을 찾고, 판단하고, 다시 잇고, 확인하는 절차다. 관리자 API와 화면은 없다. 지표 `shoppinglive.payment.unresolved.count{kind=refund_unknown}`가 0보다 크면 이 절차를 쓴다.

## 대상과 범위

- **이 절차의 대상:** `refund`의 `status = 'UNKNOWN'`이고 `next_attempt_at IS NULL`인 행. 고객 환불(`USER_REFUND`)과 시스템 보상(`SYSTEM_COMPENSATION`)이 모두 여기에 든다.
- **이 절차의 대상이 아닌 것:** 결제 자체가 조회 마감을 넘긴 UNKNOWN 결제(`kind=payment_unknown`). 거래 대사가 맡는다. 재개 SQL은 `payment`를 건드리지 않는다.
- **금액과 상태를 바꾸지 않는다.** 재개 SQL은 재시도 시각과 조회 마감만 바꾼다. 환불 결과는 실행기가 토스 응답으로만 정한다.

## 1. 멈춘 건 찾기

<!-- sql: find-stuck-refund -->
```sql
SELECT refund_request_id, payment_id, purpose, status, amount, attempt_count, created_at, check_deadline_at
  FROM refund
 WHERE status = 'UNKNOWN' AND next_attempt_at IS NULL
 ORDER BY created_at;
```

조회에는 결제키·고객 정보가 나오지 않는다. 필요하면 `payment_id`로 결제를 따로 본다.

## 2. 판단

각 행을 아래 순서로 본다. 하나라도 걸리면 재개하지 말고 보고한다.

| 확인 | 재개해도 되는 경우 | 멈추고 보고하는 경우 |
|---|---|---|
| `created_at` | 14일 이내. 토스 멱등 키 유효기간(15일) 안이다 | 14일을 넘김. 같은 키로 다시 보낼 수 없다. 제품 결정이 필요하다 |
| 결제 상태(`payment.status`) | 고객 환불은 `APPROVED`·`PARTIALLY_REFUNDED`, 시스템 보상은 `APPROVED`. 보상은 환불액이 0이 아니면 재개해도 `NOT_REFUNDABLE` 또는 보류로 끝난다 | `CANCELED`·`REFUNDED` 등. 돈이 이미 돌아갔을 수 있다 |
| 토스 결제 상세의 취소 내역 | 이 건의 취소가 있거나 없음. 고객 환불은 사유 `고객 환불 {refund_request_id}`, 금액은 환불 금액이다. 시스템 보상은 사유 `승인 응답 불일치`, 금액은 결제 전액이다 | 같은 금액이지만 다른 사유의 취소만 있음. 누가 취소했는지 먼저 확인한다 |

재개 뒤 실행기는 토스 조회부터 한다. 취소 내역이 있으면 재요청 없이 결과를 반영한다. 없으면 같은 멱등 키로 다시 취소를 보낸다. 키는 고객 환불이 `refund_request_id`, 시스템 보상이 `cancel-{pg_order_id}`다. 그래서 재개는 중복 환불을 만들지 않는다.

## 3. 재개

2번 판단을 통과한 건만 재개한다. `:ids` 자리에 그 건의 `refund_request_id`를 `'id1', 'id2'`처럼 넣는다. ID 조건은 빼지 않는다. 빼면 "멈추고 보고"할 건까지 재개된다. `BEGIN` 뒤에 실행하고, 영향 행 수가 넣은 ID 수와 같을 때만 `COMMIT`한다. 다르면 `ROLLBACK`한다.

<!-- sql: resume-stuck-refund -->
```sql
UPDATE refund
   SET next_attempt_at = now(),
       check_deadline_at = now() + INTERVAL '24 hours',
       updated_at = now()
 WHERE status = 'UNKNOWN'
   AND next_attempt_at IS NULL
   AND created_at > now() - INTERVAL '14 days'
   AND refund_request_id IN (:ids);
```

- 조회 마감을 24시간 뒤로 잡는 이유: 실행기는 마감이 이미 지났으면 조회만 하고 멈춘다. 마감을 되돌리기만 하면 재개 즉시 다시 멈춘다. 마감을 `payment.recovery.refund-give-up-after`(기본 24시간) 뒤로 다시 잡으면 실행기의 첫 시도 시각이 재개 시각이 되어, 조회 간격과 상한이 처음 규칙을 따른다.
- `next_attempt_at = now()`는 다음 실행이 바로 집게 한다. 집은 뒤 실행기가 선점 시각을 3분 뒤로 미룬다.
- 상태와 금액은 바뀌지 않는다. 바뀌는 것은 시각 두 개뿐이다.

## 4. 처리 후 확인

재개한 뒤 최대 몇 분 기다린다(선점 3분, 이후 재시도 간격). 그 뒤 다음을 확인한다.

1. 1번 조회를 다시 실행해 재개한 건이 목록에서 빠졌는지 본다. 빠진 것은 `SUCCEEDED`나 `FAILED`로 확정됐다는 뜻이다.
2. 같은 건이 다시 `next_attempt_at IS NULL`로 멈췄다면 `[사람 확인 필요]` ERROR가 다시 남는다. 이때는 토스의 취소 내역과 결제 상태를 직접 확인하고 보고한다.
3. 지표 `shoppinglive.payment.unresolved.count{kind=refund_unknown}`가 내려갔는지 본다.
4. 확정된 건은 `payment.refund.results.v1`에 결과가 나갔는지 본다(orderRef 키).

재개를 되돌리는 SQL은 없다. 실행기가 같은 멱등 키로 다시 보낸 취소만 토스에 남는다. 취소가 잘못됐다면 토스에서 직접 되돌리고 원장 정정을 요청한다.
