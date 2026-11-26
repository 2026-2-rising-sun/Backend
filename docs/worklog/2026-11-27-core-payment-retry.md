# 결제 결과 불명·보상 대기·승인 거절의 주문 처리 (#416, #417, #418)

결제 재설계(#337)의 마지막 CommerceCore 범위다. 원칙은 "만료나 처리 기한 전에는 주문을 끝내거나 예약을 풀지 않는다. 예외는 구매자의 명시적 취소뿐"이다.

## 범위와 기준

- 기준: `origin/dev` eebf22ce. 스택 `feat/#416-unknown-keep-confirming` → `feat/#417-release-on-compensation` → `feat/#418-retry-after-decline`.
- 원본: 이슈 #416~#418 본문, Payment 목표 설계 아티팩트(2026-11-27 확인), `docs/design-docs/flows/order-payment.md`.
- Payment 코드와 이벤트·API 계약 필드는 바꾸지 않았다. 설계·흐름·계약 설명 문서는 범위가 끝난 뒤 별도 문서 PR에서 고친다.

## Spec

| 이슈 | 목표 동작 |
|---|---|
| #416 | Payment가 UNKNOWN으로 답해도 처리 기한(600초) 전에는 `PAYMENT_CONFIRMING`을 유지한다. 기한 전 승인(조회·`PaymentApproved`)은 `PAID`, 기한 도달 때 보상 요청을 주문당 한 번 만든다 |
| #417 | `COMPENSATION_PENDING`으로 바꾸는 트랜잭션에서 재고·쿠폰 예약을 한 번 해제한다. 보상 결과는 다시 해제하지 않는다. 종료 상태는 `CANCELLED` |
| #418 | 승인 거절이 확정되면 `PENDING_PAYMENT`로 되돌리고 예약을 유지한다. 원래 만료 시각 전까지 새 결제창으로 재결제한다. 거절된 인증 결과는 재사용하지 못한다. 거절마다 `PaymentFailed`를 보내지 않는다. Payment `CANCELED`는 지금처럼 주문을 끝낸다. 202 뒤 거절도 포함한다 |

## Plan과 결정

- #416: `approvalUnknown`은 전달을 UNKNOWN으로 기록만 한다. 보상은 기한 판정(`OrderSheetLifecycleService.expire`)이 유일하게 만든다.
- #417: `payment_group.compensation_released_at`(V118)으로 해제를 한 번만 한다. 값이 없는 배포 전 보상 대기 행은 보상 완료 때 한 번 해제한다.
- #418: 재결제 기한은 원래 주문 만료 시각이다. 승인 전달은 시도마다 남긴다(V119: 진행·성공 시도는 주문당 하나, 같은 멱등 키는 같은 시도). 거절 때 Core 결제창(`pg_order_id`)과 송신 표시를 비운다. 거절 판정은 422 `DECLINED`와 "조회가 CREATED이고 결제창이 시도의 것과 다름" 두 가지다. 후자는 202 뒤 거절과 UNKNOWN 뒤 ABORTED·EXPIRED를 함께 덮는다.
