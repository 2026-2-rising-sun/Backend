# Order 별도600초 기한과 팀원 Payment 연동

상태: 진행 중. Core 구현·계약 대역 검증을 제공하고 실제 환불 통합과 운영 이행은 미완이다. 사용자는 CommerceCore 한정 dev 병합을 승인했다. PR Draft/ready·실제 병합은 코디네이터가 수행하며 SDK/Frontend/Payment 전체 완료를 이번 Core 병합 조건으로 확대하지 않는다. 배포 완료를 뜻하지 않는다.

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
| feat/#338-payment-approval-api / feat/#349-payment-recovery | 최신3f6bbfaa / 800b41fd, 실행2c6aa250 | 승인 API·PaymentApproved 생산·복구 실제 구현. 2c6→800b 차이는 TossClientConfigurationTest 한 파일뿐이며 생산/리소스/API/이벤트 차이0. 불변2c6 앱으로 정상/늦은 승인과 실제8초202 뒤 Kafka 승인을 검증 |
| feat/#338-toss-client | 최신887efe34 | 클라이언트 작업. Commerce 승인·환불 E2E 증거 아님 |

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
- [ ] UNKNOWN 복구중단의 Commerce 통지/조회 필드. 최신 팀원800b41fd도30분 뒤 next_check_at=NULL과 오류로그로 조회를 중단하지만 GET 응답에는 중단 사실이 없다. Core는 실패를 추정하지 않고 조회를 유지함.
- [ ] V105 이전 확인중 null 기한과 과거 다른 V104/Payment V4 적용 환경의 운영 이행.
- [x] 구매자 checkout.skuId 명시·새 active 조회/미연결 내부 주문409. PostgreSQL에서 소유 격리·정확 만료·가격 재검증·복수SKU/삭제/판매중지 회귀 검증.
- [x] Frontend 실제 브라우저의 새 주문서 Cart·단건·인증 취소/실패·사용자 주문취소·legacy 재진입·늦은 승인 환불 요청 표시. 고정 Core7a64bbbd의 실제 HTTP/DB/Kafka와 PG·세션 대역을 사용했다. 늦은 승인은 복구조회 비활성 환경에서 Kafka 전이를 분리했다.
- [ ] 실제 SDK/실 PG 검증. 위젯 흐름은 팀원/공식 문서에 있으며 Frontend94b1bee1의 주입형 provider/callback 소스와 Core4e99a0f5를 SDK 인터페이스 대역으로 연결했다. 실제 키 세트·customerKey 운영 공급·원격SDK/실PG 실행은 미확인이다.
- [ ] 신규 사용자 수량 환불은 #345, 공개 다중 SKU/옵션 정책은 #340 보류 유지.

V106은 Core seller_order/order_payment_relay/payment_relay_refund와 기존 전달 종류 확장이다. 기존 번호·금액·저장기한·전달 원문을 덮어쓰지 않는다. 새 내부 주문번호는 기존 이벤트 계약과 같은 PG-UUID(39자), seller 번호42자이며 기존 UUID 주문번호도 변경하지 않는다. legacy의 late_approval_refund(REQUIRED)는 새 원결제 ID 환불표와 분리하고 자동 이관하지 않는다.

사용자 직접 확정인600/600·확정 실패 FAILED를 적용한다. 팀원 Commerce 제안의 T+3분/승인 거절 뒤 PENDING 복귀를 새 정책으로 도입하지 않는다. Payment API/이벤트 wire와 Payment 소유 코드는 변경하지 않는다.

[실행 결과와 실패 이력](../../../worklog/2026-11-16.md)

## 늦은 HTTP 응답의 역행 방지

준비 결과는 원결제 ID를 최초로 연결할 때만 저장한다. 중복 준비 응답이 새 checkout의 pgOrderId나 확정 결과를 덮지 않는다. 거절 확정 뒤 도착한202/미확정 조회도 DECLINED를 되돌리지 않는다. 이후 실제 승인이 확인되면 별도 승인 경로가 종료 주문을 유지하며 늦은 환불 요청을 기록한다. 이 두 순서를 실제 PostgreSQL에서 실패 재현 후 보완했고 이전 cf79be0a의 Core701건은 실패·스킵 없이 통과했다. 실제 환불 통합과 운영 이행 미완은 유지한다.

## Frontend와 이전 DB 후속

[고정 API/SDK 인계](frontend-relay-handoff.md) · [이전 DB의 기술적 이행과 금전 재개 경계](archived-database-cutover.md). 체크섬 정리를 전용 복제 DB에서 검증해도 기존 확인중 NULL 기한이나 Payment 종료·보상 명령의 실제 결과를 결정한 것은 아니다. 원문과 번호·금액·기한을 보존하며 운영 이행은 계속 미완으로 둔다.

보존 정책2 진행중 주문은 legacy0이나 새 연결 주문으로 취급하지 않는다. 새 active에서 조정 필요409를 반환하며 원래 정책·상태·선점·전달을 보존한다. 이 경계의 누락을 실제 PostgreSQL에서409기대/204실제로 재현한 뒤 보완했고 관련30건이 통과했다. 수정 전 전체707건과 중복 합산하지 않는다.
