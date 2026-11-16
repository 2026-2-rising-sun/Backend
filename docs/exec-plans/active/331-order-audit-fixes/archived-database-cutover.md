# 이전 주문·Payment DB를 보존하는 이행 검토

상태: 기술적 복제 검증 진행 중, 기존 금전 처리 재개 승인 아님. 운영·공유 DB에는 실행하지 않았다. 주문 번호나 기한을 새 값으로 덮어 쓰지 않는다.

## 왜 자동 업데이트가 막히는가

보존 ref `archive/order331-before-payment-scope-correction`(`e08b8aea7e5861f7bdbbd84422e2ba3b1aae2fd0`)의 Core V104는 현재 V104보다 `order_payment_closure`, 환불 목적과 종료 요청 ID가 추가된 판본이다. 이미 그 SQL을 적용한 DB는 현재 소스로 시작하면 Flyway 체크섬 검사에서 멈춘다. V107을 새로 추가해도 그 전에 실패하므로 해결되지 않는다. 이미 적용한 SQL을 덮어쓰거나 검사 자체를 끄면 이 차이를 숨기게 된다.

보존 V104의 Flyway 체크섬은 `1274506072`, 파일 SHA256은 `00c1c1cce03575e7d103e9f124525a1d0b25cde8afe7259cb81a84f0b7cc3991`이다. [Core 테스트 fixture](../../../../services/commercecore-service/src/integrationTest/resources/order-migration/V104__order_sheets_and_closure.sql)는 그 원문이며 현재 생산 마이그레이션이 아니다. V1~103 원본은 두 ref에서 동일하다. 다른 체크섬·스키마는 이 검증 결과의 적용 대상이 아니다.

## 복제 환경에서 검증하는 순서

1. 기존 Core·Payment를 각각 백업하고 원문 SQL/ref·Flyway 이력·제약·데이터를 함께 보관한다. 미확정 전달을 실행할 worker는 연결하지 않는다.
2. Core 복제본에 보존 V104를 적용하고 정책1 확인중 주문, 15분 생성기한, 원래 주문 번호/금액/Cart 버전, UNKNOWN 전달 원문, 종료 기록과 보상 환불 출처를 심는다. 현재 소스 이행이 체크섬 불일치로 실패하고 데이터가 그대로인지 검사한다.
3. 이 특정 복제본만 V104 이력 메타데이터를 현재 체크섬과 대조·정리한 뒤 V105/V106을 적용해 기술적 DDL 호환을 확인한다. 테스트는 V104 한 건만 불일치하며 V1~103 체크섬이 일치하는지 먼저 검사한다. 실제 업무 행·추가 열·종료 표를 제거하거나 금전 명령을 재전송하지 않는다. 운영의 일괄 `repair` 또는 자동 체크섬 변경 기능은 추가하지 않았다.
4. 원래 행의 모든 비NULL 필드 JSON을 전후 대조한다. 신규 처리기한은 NULL 그대로, 새 Payment binding/seller 주문/환불 요청은 임의 생성하지 않는다. 새 인증 후 승인 계약에 대응하는 시작시각을 확인하지 못한 행은 `created_at+600`이나 현재 시각으로 채울 수 없다.
5. Payment 보존 V1~V4는 별도 전용 DB에 원문 그대로 적용한다. UNKNOWN 승인·환불·`payment_order_control` 표본을 보관하고 Core 검증 후 값/제약이 그대로인지 대조한다. Payment V4를 고치거나 새 팀원 Payment 스키마로 자동 전환하지 않는다.

## 실행 재개를 막는 데이터

[읽기 전용 Core 점검 SQL](../../../../scripts/sql/order-cutover-audit.sql)은 V106 복제본의 확인중 NULL 기한·미연결 정책1/2·미확정 legacy 전달·과거 종료 표/열을 보여 준다. 읽기 전용 트랜잭션이며 전달 본문은 해시만 출력한다. 기술적 이행 통과와 실제 승인·환불 대사는 별도다.

이런 행이 있으면 새 Payment API에 예전 전달을 그대로 재생하거나, 정책0으로 바꾸거나, 주문을 새로 만들거나, 확정실패/환불완료로 처리하지 않는다. 원래 Payment의 승인/환불/종료 수신 여부와 실제 승인 시작시각을 확인해야 한다. 과거 `PaymentDeliveryStore.payment()`는 legacy 실행 접수 때 `requested_at`을 같은 TX에 저장했으므로 대사 후보 시각은 존재한다. 그러나 보존 OrderSheet는 최초 생성기한으로 진행중 만료를 판정했고 새 인증 후 APPROVAL 계약·별도600초를 사용하지 않았다. 그 시각에 새 정책을 소급 적용하거나 원문을 새 Payment ID/PG key로 변환하는 일은 기술적 메타데이터 정리와 다르다. 확인중 NULL 기한과 오래된 종료·보상 기록은 과거 실행 결과 대사 없이 결정할 수 없으므로 금전 worker 재개는 보류다. 현재 환경의 데이터 손실이나 임의 기한 선택을 사용자에게 승인받았다고 주장하지 않는다.

실행 결과·최초 실패·수정·실제 PostgreSQL/Payment 스냅샷 근거는 [작업 기록](../../../worklog/2026-11-16.md)에 이어 남긴다. 전체 계획은 active·PR343은 Draft로 유지한다.
