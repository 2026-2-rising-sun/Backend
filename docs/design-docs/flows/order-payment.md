# 주문·결제 서비스 간 흐름

<!-- visual: relations -->

새 주문서 경로는 결제창 준비와 실제 승인을 나눈다. 준비 동안 생성부터600초 PENDING을 유지하고 인증 후 승인 접수에서 PROCESSING 별도600초를 시작한다. 기존 공개 legacy 경로도 유지한다. Payment 실행·원장은 팀원 소유이며 Core는 주문·재고·쿠폰과 전달 기록을 소유한다.

기존 Mock 실행 설명은 #337에서 교체할 예정이며 목표 설계 원본은 [Payment 도메인 홈](../services/payment/overview.md)이다. 이 문서의 새 경로는 Core 소유 어댑터와 주문 상태를 설명한다. 이 저장소 dev의 Payment 설명과 별도 팀원 구현 브랜치를 실행한 증거를 구분한다.

## 새 계약 경로

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 사용자 | Core 주문서 | SKU/Cart·예상 금액 검증, 선점·판매자별 금액/설정 수수료·PREPARE 전달 원문 같은 TX |
| Core 커밋 후 | Payment 생성/checkout | 준비 데이터만 저장. PENDING/원래 기한 유지 |
| 사용자 인증 완료 | Core PROCESSING | 첫600초 처리기한·APPROVAL 원문 같은 TX. 반복 요청은 불연장 |
| Core 커밋 후 | Payment 조회/승인 | 먼저 조회, 유효한 미접수만 승인. 202/UNKNOWN·통신 예외는 실패가 아님 |
| Payment 확정 승인 | Core PAID | HTTP 조회 또는 PaymentApproved 검증·멱등 반영·재고 소진/쿠폰 사용 |
| Core 기한 경계 | EXPIRED | 재고/쿠폰 선점 한 번 해제. 이후 새 승인 POST 없이 조회 유지 |
| 종료 주문의 늦은 승인 | 환불 필요+Outbox | 주문 부활 없이 late-{paymentId}와 RefundRequested(LATE_APPROVAL) 같은 TX |
| Core 환불 요청 발행 | Payment 환불 소비 | 실제 소비자는 아직 미발견. 발행 성공은 환불 접수/완료가 아님 |
| Payment 환불 결과 | Core 환불 상태 | Core 소비자는 계약 대역으로 검증. 금액/판매자 원문 검증 뒤 SUCCEEDED/FAILED, 주문은 종료 상태 유지 |

팀원 복구2c6aa250의 변경 없는 앱과 실제 Core/PostgreSQL/Kafka로 정상 승인, 처리 중 만료 뒤 늦은 승인, 실제8초202 뒤 Kafka만으로 PAID 반영을 확인했다. PG는 HTTP 대역이며 실 PG와 실제 환불 실행은 미실행이다. 이전 Mock/legacy 검증을 새 설계 E2E로 세지 않는다.

## 기존 경로와 이행

legacy PAYMENT/REFUND 전달은 기존 requestId/원문을 유지한다. 기존 0원 경로도 유지하며 새 계약의 양수 제약은 새 order-sheets에만 적용한다. legacy의 late_approval_refund(REQUIRED)와 새 원결제용 payment_relay_refund(REQUESTED)는 별개다. 과거 번호/기한/금액/기존 null 처리기한을 임의 재작성하지 않는다. Frontend 호출자 전환과 SDK 대역 브라우저 검증은 확인했다. 실제 위젯 설정·실SDK/실PG·실환불 및 과거 미확정 거래의 운영 대사는 후속이다. 사용자는 이를 구분해 CommerceCore 범위의 dev 병합을 승인했으며 이번 통합은 운영 결제·배포 완료를 뜻하지 않는다.

[Core 상세](../services/commercecore/order/overview.md) · [현재 원본·남은 기준](../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [실행 기록](../../worklog/2026-11-16.md) · [팀원 설계](../services/payment/overview.md)

## 이전 Mock 설명의 기준

기존 공통 설명은 `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사와 #330 문서 변경을 기준으로 했다. 당시 업무 코드나 실제 DB·HTTP·Kafka·PG 실행을 검증한 것으로 보지 않는다. 기존 Mock의 PAYMENT_CONFIRMING+PAYMENT 전달, Payment Mock 실행/결과 Outbox, Core의 재고·쿠폰 결과 반영은 legacy 경로의 설명이며 새 승인/환불 전체 통합의 증거로 합산하지 않는다.
