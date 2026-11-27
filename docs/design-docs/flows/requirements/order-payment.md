# 주문·결제 수용 기준

상태: 진행 중
검증: [현재 흐름](../order-payment.md) · [Core 보완 계획](../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [실행 기록](../../../worklog/2026-11-16.md)

## 이번 CommerceCore 병합 범위

사용자는 CommerceCore 구현·검증 범위의 dev 병합을 승인했다. 아래 Core 수용과 외부 후속을 구분하며 전체 설계를 완료 처리하지 않는다. 실제 병합은 코디네이터가 누적 모듈 순서로 수행한다.

- [x] 공개 주문서 생성에서 항목별 SKU·가격·판매자·Cart 출처를 보존하고 PENDING600초 동안 선점한다.
- [x] 결제창 준비는 PENDING을 유지하고 인증 후 승인 접수에서만 PROCESSING 별도600초와 HTTP 전달 원문을 같은 TX에 저장한다. 반복 요청으로 연장하지 않는다.
- [x] 같은 멱등 키·원문은 같은 주문/전달로 연결하고 커밋 후 HTTP 재전달·조회로 복구한다.
- [x] 처리기한(승인 시도 접수부터 600초) 안의 미확정 결과(202·UNKNOWN·통신 예외)는 PAYMENT_CONFIRMING과 선점을 유지하고 1초마다 Payment를 다시 조회한다. 기한 전 승인은 PAID다(#416).
- [x] 결제 대기(PENDING_PAYMENT)가 생성 기한에 닿으면 EXPIRED로 끝내고 선점을 한 번 해제한다.
- [x] 결제 확인 중(PAYMENT_CONFIRMING)이 처리 기한에 닿으면 COMPENSATION_PENDING으로 바꾸고 같은 TX에서 선점을 한 번 해제하며 시스템 보상(`APPROVAL_UNKNOWN`)을 주문당 한 번 요청한다(#416·#417).
- [x] 보상 없이 닫힌 주문(EXPIRED/CANCELLED/FAILED)의 늦은 승인은 주문 부활 없이 `LATE_APPROVAL` 보상 요청 기록/Outbox로 처리한다.
- [x] 승인 거절(422 `DECLINED`, 또는 조회가 CREATED이고 결제창이 시도와 다름)은 PENDING_PAYMENT로 되돌리고 선점을 유지한다. 원래 생성 기한 전까지 같은 주문에서 새 결제창으로 재결제하며 거절마다 PaymentFailed를 보내지 않는다(#418).
- [x] 재결제는 시도마다 새 `X-Idempotency-Key`를 쓴다. 같은 키·같은 본문의 재전송은 그 시도의 결과를 돌려주고 새 승인을 만들지 않는다. 거절된 시도의 키를 다른 결제창 정보로 보내면 409이고 문구 "거절된 승인 시도의 키입니다. 재결제는 새 X-Idempotency-Key로 요청해 주세요."로 구분된다(#418).
- [x] HTTP/Kafka 확정 결과 중복으로 재고·쿠폰을 이중 반영하지 않는다.
- [x] 기존 번호·금액·생성기한과 확인중 NULL 기한을 보존한다. 복제 DB에서 과거 V104/Payment V4 구조·원문 보존을 검증했다.
- [x] 단일 SKU의 명시적 식별자와 새/기존 방식 진행중 주문 조회를 제공한다. 이전 구현의 새 결제 연동 기록 없는 주문을 없음으로 숨기지 않는다.

## 서비스 공동 후속과 보류

- [x] Payment 환불 요청 소비와 성공/실패/NO_CHARGE 결과 생산을 연결한다(이슈 #385·#370·#386, PR #394~#396).
- [ ] UNKNOWN 복구중단의 알림을 실제로 확인한다. 지표는 #387(PR #413), 알림은 Infra #64다.
- [ ] 과거 확인중 NULL 기한·미확정 승인/환불/종료 행을 원래 Payment 결과와 대사해 운영 재개 조건을 확정한다. 복제 DB 구조/데이터 보존은 금전 처리 재개 증거가 아니다.
- [ ] 실제 위젯 설정/키·customerKey 공급과 원격 SDK/실 PG를 검증한다. 이번 Core 병합의 필수조건으로 확대하지 않는다.
- [ ] 사용자 수량환불·동시환불은 #345, 공개 다중 SKU/옵션은 별도 보류를 유지한다.

## 기존 Mock 기준과 Payment 목표 설계

기존 Mock 설명은 결제 재설계(#337)에서 교체됐으며 새 Payment 설계 원본은 [Payment 도메인 홈](../../services/payment/overview.md)이다. Payment 쪽 Mock 실행 코드와 내부 API는 #347에서 삭제됐다(테이블은 #348 V4에서 삭제). Core의 기존 결제·환불 호출은 #390에서 삭제됐고, 새 중계는 `/v1/internal/payments` 생성·결제창·승인·조회만 부른다. 이전 동일 키/원문 연결·유효 확인중 예약 유지·중복 결과 방지 기준은 위 Core 조건으로 검증 범위를 명시했다. [이전 보존 기록](../../../worklog/plans/completed/331-order-module/verification.md)을 최신 실제 PG/환불 실행 완료로 재사용하지 않는다. Payment 소유 원문·계약은 이 작업에서 임의 변경하지 않는다.
