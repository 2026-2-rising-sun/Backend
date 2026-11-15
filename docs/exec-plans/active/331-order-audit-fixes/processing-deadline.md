# Order 별도600초 기한과 새 Payment 연동

상태: 진행 중. 전체 연동 미완·PR343 Draft·머지/배포 불가.

## 현재 제공

새 공개 주문 기본600초, 기존 생성 기한 보존, 첫 실행의 별도600초와 HTTP 전달 요청의 원자적 저장을 제공한다. 공개 상태명은 호환 보존한다. HTTP 전달 기록의 생성/재시도도 조회와 같은 DB시각을 쓴다. DB시각 기준 정확 경계에서 만료하며 승인/만료를 회원 guard→묶음 행 잠금 순서로 직렬화한다. 늦은 승인에는 재고를 재선점하지 않고 `late_approval_refund(REQUIRED)`를 결과와 같은 트랜잭션에 저장한다. 내부 주문서 만료도 EXPIRED로 표현한다.

## 원본 대조: 2026-11-16 fetch

| 원본 | 확인한 리비전 | 사실 |
|---|---|---|
| origin/dev | d8b195b01871bb84bcae098eff8f3d8f0896a84f | #339/#350/#351 설계 문서 병합. 실행 API는 기존 PaymentCommand/PaymentExecution 방식 |
| feat/#348-payment-core-schema | 1075ed7a80346498f620ceb54cb3936a94c4ffd2 | 새 스키마·엔티티 존재. 생성/승인 컨트롤러와 PaymentApproved 발행/RefundRequested 소비 구현 없음 |
| feat/#338-toss-client | 0814c68036bf0d9549d90709b18b523a3a06f701 | 클라이언트 별도 작업. Commerce 생성/승인/환불 연동 완료 증거가 아님 |
| refactor/#347-payment-legacy-removal | b4b11ea9e7de27d6a45fbb83b55d2007ab94ffc6 | 기존 실행 제거 작업. 이 브랜치를 병합하거나 Core 경로를 제거하지 않음 |

`git show <ref>:<path>`와 `git grep <ref> -- services/payment-service/src/main/java contracts/events`로 읽었다. 다른 worktree·Payment 소유 소스·DB·API/이벤트 원본을 변경하지 않았다.

팀원 목표는 dev의 `docs/exec-plans/active/337-payment-redesign/phase1/348-create-api.md`, `phase1/338-approval-ledger.md`, ADR0337-1/3, refund 요구사항이다. 생성 요청 sellerAmounts에 필수인 commissionRate 원본이 Core에 없고 sellerOrderNumber의 판매자 묶음/할인 배분 확정도 필요하다. 임의 비율은 만들지 않으며 사용자에게 수수료 원본을 질의했다.

## 남은 수용 기준

- [x] 새 주문 생성 기본600초·첫 실행 별도600초·재요청 불연장·원래 생성 기한 보존.
- [x] 전달 기록과 상태 전환의 같은 로컬 트랜잭션, 기존 커밋 후 전달/동일 requestId 재시도 유지.
- [x] 기한 경계의 승인/만료 직렬화, 늦은 성공 필요 기록, 실패 후 중복 선점해제 방지.
- [ ] Payment 생성/준비 성공은 PENDING 유지, 실제 인증 완료 승인 요청만 PROCESSING 전환하는 공개 중계.
- [ ] 실제 승인 API·조회 상태/실패 확정/UNKNOWN 복구중단 계약 연결. 202를 실패로 보지 않는 새 어댑터.
- [ ] sellerAmounts·commissionRate·sellerOrderNumber의 확정 원본과 할인 배분.
- [ ] 늦은 승인 필요 기록 + RefundRequested outbox 원자적 저장, 실제 Payment 소비자와 환불 결과 반영.
- [ ] 새 PaymentApproved/환불 Kafka 중복 소비, 실제 새 Payment HTTP 응답 유실과 조회 복구.
- [ ] V105 이전 확인중 주문과 과거 다른 V104/Payment V4 적용 환경의 운영 이행.
- [ ] 공개 주문서·실제 신규 수량 환불. #340/#345 별도 보류 유지.

현재 legacy dispatcher는 저장된 requestId로 재전달한다. 새 Payment의 CREATED expiresAt과 PROCESSING 기한을 혼동하면 안 된다. 처리 기한 후 미전달 승인/접수 여부 불명 요청의 조회 전환은 새 Payment 계약과 함께 마무리해야 한다. 현재 REQUIRED 기록만 있는 상태는 자동 환불 제공 상태가 아니므로 배포하지 않는다.

검증은 [2026-11-16 워크로그](../../../worklog/2026-11-16.md)에 기록한다. 이전1339건과 CI658건을 이번 변경의 통과로 재사용하지 않는다.
