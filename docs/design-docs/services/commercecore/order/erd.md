# Core 주문·환불 물리 매핑

<!-- visual: relations -->

[업무·상태](overview.md) · [API](api.md) · [Core ERD](../erd.md)

## 관계

| 출발 | 도착 | 조건·참조 |
|---|---|---|
| 논리 Order | payment_group | 기존 PK·번호·정책·생성기한 유지. PAYMENT_CONFIRMING 기한 별도. 사용자 환불 진행은 refund_request가 가진다 |
| payment_group | orders | 불변 SKU/상품/판매자/수량/가격/할인/Cart출처. 항목별 PAID·PARTIALLY_REFUNDED·REFUNDED (이전 정책 행만 CANCEL_PENDING 가능) |
| payment_group | seller_order | 판매자별 생성 당시 실결제액·수수료율·외부 판매자주문번호 |
| payment_group | order_payment_relay | 원결제ID·결제창·별도 승인 사실·approval_send_started_at. 송신 시작/보상을 같은 그룹 잠금으로 정렬하며 HTTP 동안 잠금 유지 없음. 주문 종료 뒤에도 승인 사실 보존 |
| payment_group | payment_delivery_request | 기존 PREPARE/APPROVAL HTTP 원문. 신규 환불 HTTP 전달은 만들지 않음 |
| payment_group | core_refund_intent | 고정UUID·요청 원문·purpose·상태·outcome·executionId·resultVersion·결과 원문. 시스템은 그룹당 단일행·영구 fence |
| core_refund_intent | refund_request | USER_REFUND만 user_request_id로 연결. 회원+키·fingerprint·금액·PROCESSING/UNKNOWN/SUCCESS/FAILED |
| refund_request | refund_target_order | 주문별 요청 quantity·refund_amount·previous_status·상품/판매자 스냅샷. 기존 PK(request,order) 보존 |
| refund_target_order | orders | 성공 요청의 수량·금액 누계를 원수량/실결제액과 대조. 같은 결제 미완료 접수 직렬화 |
| core_refund_intent | core_refund_result_event | eventId PK·요청 FK·수신 봉투 원문. 동일 ID 다른 본문 거절 |
| 주문·환불 TX | event_outbox | 요청과 자원·주문 결과 알림을 원자 저장. 공통 환불은 relay, Catalog CDC와 별도 |
| orders | SKU | sku_id 우선·과거 sales_info_id 호환. 예약 release와 판매완료 restock을 구분 |
| orders Cart 출처 | Cart ID/version | 결제 뒤 Cart 삭제와 무관하게 선택·환불 근거 보존 |
| payment_group | member_coupon | 전수량 사용자 환불에서 used_payment_group_id 대조·원 만료 유지 복원. 시스템은 예약 release |
| payment_group | late_approval_refund/payment_relay_refund | 기존 이행 전 원문 보존. 신규 공통 보상으로 자동 재작성하지 않음 |

## migration과 경계

V104의 정책·스냅샷·대상수량·쿠폰출처, V105의 별도 처리기한, V106의 판매자/승인 relay와 구 늦은환불 원문을 유지한다. V115만 신규 공통 intent/결과event·이전 상태·CANCEL_PENDING CHECK를 추가한다. 새 테이블은 Core 내부 FK이며 Payment DB와 공유하지 않는다.

`core_refund_intent`의 SYSTEM_COMPENSATION 행은 완료 상태여도 승인 fence다. 사용자 SUCCESS/FAILED는 정상 종료 요청이며 신규 사용자 요청을 허용하되 이전 원문을 바꾸지 않는다. 결과event 원장은 요청 삭제 시 함께 삭제되지만 자동 TTL·정리 작업은 도입하지 않았다. 운영 보관 기간·격리/DLQ와 장기 보상 실패 복구는 후속 운영 과제다.

## 시스템 보상 상태 이행

V116은 orders/payment_group의 CHECK에 COMPENSATION_PENDING을 추가하고 활성 회원 유일성에도 포함한다. 첫 처리 시작600초 무응답 또는 명시적UNKNOWN에서 그룹·항목 상태와 SYSTEM_COMPENSATION 원문/Outbox를 한 TX로 기록한다. 검증된 완료 결과에서만 CANCELLED와 예약 단일해제를 확정한다. V115 원문·ID를 보존하며 기적용 미완료 보상만 대기로 이행한다. 이미 종료한 과거 주문은 유지한다. 실패 보상은 대기 상태이며 같은 요청/실행·더 높은 resultVersion 완료를 허용한다.
