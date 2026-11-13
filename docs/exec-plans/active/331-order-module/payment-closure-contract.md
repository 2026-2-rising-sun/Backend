# 실패 주문 종료 통지·늦은 성공 보상 최소 계약안

상태: 코디네이터 사전 검토 후 구현 진행 중. 구현 착수 공통 지시에서 Order #331에 배정한 Core/Payment 최소 연계이며 일반 부분 환불 정책과 독립적이다.

## 종료 통지

`PUT /v1/internal/payments/{paymentId}/order-closure`, 기존 `X-Service-Token` 인증.

본문: `closureRequestId`(UUID), `paymentRequestId`(원 결제 UUID), `orderId`(기존 항목 BIGINT), `paymentGroupId`(기존 묶음 BIGINT), `referenceNumber`, `amount`(원), `reason`(`EXPIRED`), `closedAt`(서버 시각). 응답: 같은 두 요청 ID·paymentId와 `status=RECORDED`. 같은 식별자/내용 재전송은 기존 접수 반환, 다른 내용은 409.

Core는 Order 만료/예약 해제와 같은 트랜잭션에 종료 의도를 저장하고 잠금 밖에서 전달한다. 결제 전달 요청이 없는 주문에는 외부 종료 통지를 만들지 않는다. 저장된 `paymentRequestId`와 결제 참조를 원본으로 사용한다.

Payment는 결제 생성 전에 도착한 종료도 영속 보존한다. 이후 같은 요청 생성 시 PG를 실행하지 않는 확정 실패 시도를 반환한다. 이미 진행 중인 결제의 실제 성공은 지우거나 실패로 덮어쓰지 않는다. 종료와 실행이 경합해 실제 성공했으면 아래 보상 경로가 처리한다.

## 늦은 성공 보상

Core는 만료 뒤 실제 성공을 확인하면 결제 실행 SUCCESS를 저장하고 동일 트랜잭션에 **용도 COMPENSATION인 기존 refund_request + 영속 환불 전달**을 한 건 생성한다. 주문은 FAILED/EXPIRED 유지, Cart/쿠폰 사용 확정·SKU consume/restock은 하지 않는다. 보상 성공도 재고/쿠폰을 다시 바꾸지 않는다.

기존 `POST /v1/internal/refunds`의 RefundCommand에 선택 필드 `orderClosureRequestId`를 추가하는 안이다. 일반 환불은 null로 기존 동작을 유지한다. 보상 요청은 Payment의 저장된 종료 기록과 원결제 요청/금액/참조를 대조하며 원결제 전액만 허용한다. `requestId`/`coreRefundRequestId`는 기존 환불 멱등성·누적 금액 한도를 그대로 사용한다. 보상 전용 새 결제/환불 실행 엔진은 만들지 않는다.

종료 보상은 일반 사용자 환불 기능 플래그가 꺼져도 실행·재시도해야 한다. 기존 정책 플래그를 전체 해제하지 않고 검증된 종료 보상 요청만 기존 refund engine에 전달한다. 부분 환불 U1~U3나 취소 진행 중의 신규 정책을 이 경로로 자동 허용하지 않는다.

## 저장·검증

- Core V104: 기존 PK/물리명 보존, 새 주문 정책 버전·SKU snapshot·종료 전달 의도, 기존 refund_request에 목적/종료 참조 확장.
- Payment V4__order_closure_compensation.sql이 실제 db/payment-migration 경로로 배정됐다.
- 테스트: 종료 선도착→후속 결제 생성, 동일/변경 요청 재전달, 실행과 종료 경합 후 성공 보상, 응답 유실·재시도, 중복 보상 성공, 일반 환불 플래그 off 보상, 기존 사용자 환불 호환.
- H2/Mock, 실제 PostgreSQL(두 독립 DB), HTTP/Kafka 결과 전달은 각각 구분해 검증한다.

## 검토 반영과 실제 검증

결제 생성/종료/호출 시작은 paymentId+원 paymentRequestId의 영속 제어 행을 같은 순서로 잠근다. 스케줄 예약 직후·beginInvocation 전후·실제 authorize 진행 중의 종료를 검증한다. 이미 시작한 UNKNOWN은 재승인하지 않고 조회해 실제 성공을 보존한다. attempt PK와 외부 paymentId가 다른 표본도 포함한다.

보상 선도착은 종료 반영 대기 503이며 payload 변경 409와 구분한다. 저장된 원결제당 보상 실행은 유일하고 다른 closure/refund ID로 우회할 수 없다. 플래그 off에서는 Core 전달·Payment 예약·재시작 스캔 모두 검증된 보상만 허용한다. 신규 묶음만 종료 통지 대상으로 하고 기존 nullable paymentGroupId 단건은 기존 흐름을 유지한다. 0원은 과금이 없으므로 Core 보상 환불을 만들지 않는다.

실제 PostgreSQL / Mock gateway: Payment HTTP 경계 26건, 전체 postgresTest 41건 실패·오류·스킵 0. 외부 실제 PG 실행 증거가 아니다. Core와 두 DB의 통합·Core V104 이행 검증은 선행 포트 통합 후 이어간다.
