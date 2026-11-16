# Order 별도600초 기한과 팀원 Payment 연동

상태: 진행 중. Core 구현·계약 대역 검증을 제공하고 실제 환불 통합과 운영 이행은 미완이다. PR343 Draft, 머지·배포 불가.

## 현재 제공

결제창을 준비해도 주문은 PENDING이다. 사용자의 인증 정보로 승인을 요청할 때만 PROCESSING으로 바꾸며, 생성600초와 첫 승인 접수600초를 별도로 보존한다. 같은 요청을 반복해도 기한이 늘어나지 않는다. 기존 DB enum PENDING_PAYMENT/PAYMENT_CONFIRMING은 호환 보존하고 새 응답은 PENDING/PROCESSING으로 설명한다.

새 `/v1/order-sheets`는 UUID 구매자·SKU 또는 Cart 출처·예상 실결제액·선택 SKU 쿠폰을 받는다. 생성 TX에서 주문/선점/판매자별 금액/수수료 스냅샷과 PREPARE 전달 원문을 저장한다. 인증 후 승인 TX에서는 상태·처리기한·APPROVAL 전달 원문을 함께 저장한다. HTTP는 커밋 후 호출하며 복구 작업이 같은 원문으로 재시도한다. 기존 legacy API는 유지한다.

승인 전달은 먼저 Payment를 조회한다. APPROVING/UNKNOWN/202와 통신 예외는 실패 확정이 아니다. 응답 유실 후 APPROVED를 조회하면 재승인 없이 반영한다. 조회 중 기한이 지나면 전송 전에 다시 만료를 적용하고 새 승인 POST를 보내지 않는다. 전송 직후 만료와 접수가 겹쳐도 결과 적용은 회원 guard→묶음 잠금으로 직렬화하고 늦은 성공을 환불로 보낸다. Payment CREATED의 expiresAt은 원래 생성기한이며 PROCESSING 기한으로 다시 쓰지 않는다.

유효 PROCESSING 승인만 PAID, 확정 실패는 FAILED와 선점해제다. 정확 기한이면 EXPIRED와 재고·쿠폰 해제를 먼저 적용한다. 늦은 승인은 주문을 복원하지 않고 `payment_relay_refund(REQUESTED)`와 `RefundRequested(reason=LATE_APPROVAL, refundRequestId=late-{paymentId})` outbox를 같은 TX에 저장한다. HTTP/Kafka 중복은 승인 시각·요청 유일키로 막는다. Kafka 발행과 환불 성공은 별개이며 RefundSucceeded/RefundFailed를 받아 결과만 기록한다. Payment 주문종료 API·취소 이벤트 소비 계약은 만들지 않는다.

## 팀원 원본과 실제 가용성

| 원본 | 확인 리비전 | 실제 범위 |
|---|---|---|
| origin/dev | d8b195b01871bb84bcae098eff8f3d8f0896a84f | ADR0337과 생성·승인·환불 목표 계약 |
| feat/#348-payment-create-api | a4cd70be | 생성·조회·checkout 실제 구현. 변경하지 않은 소스로 띄운 Payment/PostgreSQL에 Core HTTP 클라이언트로 생성201·재요청200·조회·checkout201·새 결제창 조회 5건 확인 |
| feat/#338-payment-approval-api / feat/#349-payment-recovery | 47d4850a / 2c6aa250 | 후속 fetch에서 승인 API·PaymentApproved 생산·복구 실제 구현 발견. 변경 없는 복구 브랜치 앱으로 정상/늦은 승인과 실제8초202 뒤 Kafka 승인을 확인 |
| feat/#338-toss-client | 0814c680 | 클라이언트 작업. Commerce 승인·환불 E2E 증거 아님 |

수수료 원본은 팀원 `docs/design-docs/services/payment/design.html`의 `commerce.commission-rate` 설정이다. 생성 시 seller_order에 복사하며 숫자 기본값을 임의로 넣지 않는다. seller_id 정렬 순서로 `{orderRef}-S1`부터 번호를 만들고, 각 판매자의 항목 총액에서 해당 선택 SKU 쿠폰 할인을 뺀 실결제액을 전달한다. 테스트의 0.1250은 fixture다. 설정 누락은 신규 생성 TX를 rollback하며 기존 legacy 경로를 변경하지 않는다. 이전 조사에서 이 원본과 생성 API 브랜치를 누락했던 설명은 정정한다.

## 수용 기준과 남은 의존성

- [x] 생성600초·첫 승인600초·재요청 불연장·원래 생성기한 보존.
- [x] 준비 성공 PENDING 유지, 실제 인증 후 승인 요청만 PROCESSING.
- [x] 상태/기한/HTTP 전달 기록 같은 TX, 커밋 후 전송·동일 원문 재시도·조회 복구.
- [x] 판매자별 번호/할인 후 금액/설정 수수료 스냅샷.
- [x] 202/UNKNOWN 유지, 승인·만료 경합, 실패 후 중복 해제 방지.
- [x] 늦은 승인 환불 기록+Kafka outbox 원자 저장, 승인/환불 결과 멱등 소비. 실제 PostgreSQL+EmbeddedKafka에서 계약 대역 이벤트 중복·발행·결과 반영 검증.
- [x] 팀원 실제 승인 endpoint·PaymentApproved 생산을 Core HTTP/Kafka와 연결. PG는 대역이며 실 PG 증거는 아님.
- [ ] 팀원 실제 RefundRequested 소비/환불 결과 생산을 통한 끝까지 검증. 현재 구현을 발견하지 못해 완료 시각을 정할 수 없음.
- [ ] UNKNOWN 복구중단의 Commerce 통지/조회 필드. 팀원2c6aa250은30분 뒤 next_check_at=NULL과 오류로그로 조회를 중단하지만 GET 응답에는 중단 사실이 없다. Core는 실패를 추정하지 않고 조회를 유지함.
- [ ] V105 이전 확인중 null 기한과 과거 다른 V104/Payment V4 적용 환경의 운영 이행.
- [ ] 기존 호출자/Frontend의 새 주문서 전환과 실 PG 검증. 기존 legacy가 새 계약으로 전환됐다고 주장하지 않음.
- [ ] 신규 사용자 수량 환불은 #345, 공개 다중 SKU/옵션 정책은 #340 보류 유지.

V106은 Core seller_order/order_payment_relay/payment_relay_refund와 기존 전달 종류 확장이다. 기존 번호·금액·저장기한·전달 원문을 덮어쓰지 않는다. 새 내부 주문번호는 기존 이벤트 계약과 같은 PG-UUID(39자), seller 번호42자이며 기존 UUID 주문번호도 변경하지 않는다. legacy의 late_approval_refund(REQUIRED)는 새 원결제 ID 환불표와 분리하고 자동 이관하지 않는다.

사용자 직접 확정인600/600·확정 실패 FAILED를 적용한다. 팀원 Commerce 제안의 T+3분/승인 거절 뒤 PENDING 복귀를 새 정책으로 도입하지 않는다. Payment API/이벤트 wire와 Payment 소유 코드는 변경하지 않는다.

[실행 결과와 실패 이력](../../../worklog/2026-11-16.md)
