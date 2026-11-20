# 2026-11-19 Core 공통 Kafka 환불

> 과거 실행 이력이다. 아래 즉시 응답유실 보상·진행 상태 유지·완료 FAILED 정책과 타팀 연동 주장은 현재 구현 설명이 아니다. [후속 정정](2026-11-20-core-compensation-state.md)의600초/명시적UNKNOWN→COMPENSATION_PENDING→검증완료CANCELLED와 Core-only 검증 경계를 따른다.

기준 `3537340c4686fec3b962da8ed02d368829d639af`, 작업 브랜치 `core-refunds`. [후속 정정·현재 상태표](2026-11-20-core-compensation-state.md) · [현재 설계](../design-docs/services/commercecore/order/overview.md). 커밋·푸시·PR·병합·배포는 수행하지 않는다.

## 구현

A 인계의 계약3개 patch와 신규23파일을 SHA256/목록/상대경로/`git apply --check`로 확인해 상속했다. 해당 계약·A 문서는 C가 수정하지 않는다. C는 신규 주문서 전체·선택·부분수량 환불, 사용자 CANCEL_PENDING·원문·Outbox atomic commit, 동일 결제 미완료 직렬화, 시스템 응답 유실/UNKNOWN의 별도 보상 intent/fence, 결과 원문·버전·멱등성·원자 자원 반영, 조회와 API/문서를 구현한다. V115를 사용하며 기존 SKU release/restock/쿠폰 포트와 기존 승인 HTTP는 보존한다.

공통 환불 요청/결과는 새 dot 토픽이며 하이픈 legacy 원문과 결과 경로는 이행용으로 보존한다. 정상202 PENDING은 보상 접수가 아니다. 실행 토글은 legacy HTTP 전용이고 신규 Kafka 접수는 원문/Outbox를 항상 기록한다. Frontend는 수정하지 않았고 새 상태·응답 필드 영향은 설계/API에 기록했다.

## 실제 실행과 미실행

최종 알림 정정·D 요청ID 수정 뒤 소스를 해시로 동결하고 아래 검사를 실제 실행했다. 실행 뒤 src 전체 해시가 동일함을 확인했다. PostgreSQL은 실제 관계형 DB 검사이며 결제 PG 공급자 검사가 아니다.

| 최종 검사 | 명령·대상 | 실제 결과 |
|---|---|---|
| 단위·컴파일 | Java21 `:services:commercecore-service:test :services:commercecore-service:compileIntegrationTestJava` | 단위203, 실패/오류/스킵0 |
| 환불 API 회귀 | `:services:commercecore-service:integrationTest --tests '*RefundApi*'` |7, 실패/오류/스킵0 |
| PostgreSQL TX·경합 | `:services:commercecore-service:postgresTest --tests '*KafkaRefundPostgresTest' --tests '*OrderSheetDomainPostgresTest' --tests '*PaymentRelayPostgresTest'` |84=신규40+주문5+승인39, 실패/오류/스킵0 |
| PostgreSQL migration | `:services:commercecore-service:migrationTest --tests '*RefundSchemaPostgresTest'` |4, 실패/오류/스킵0 |
| 포맷 | `:services:commercecore-service:spotlessCheck` | 통과 |
| API 구조 | `.github/scripts/api/check_structure.py` |128개 경로/메서드 일치. 응답 런타임 증명 아님 |
| 이벤트 | `.github/scripts/api/check_events.py --samples` |9계약·33예시·25표본. 신규 사용자/시스템 실제 Core Outbox 표본 포함. 실제 전달 증명 아님 |
| 문서 | `scripts/check-docs.sh`, API/설계 생성기 `--check` |266문서 오류0·API10쌍·설계110쌍 최신 |

Java21과 기존 Python API 가상환경을 명시했다. PostgreSQL 환경변수는 조정자가 제공한 host25434 전용 DB와 임의 schema를 사용했다. 비밀번호는 기록하지 않는다. 마지막 관련 통합 Gradle은 비영 종료 없이 통과했으며 `build/core-refund-handoff/final-verification.json`과 XML/로그에 수치·동결 근거가 있다.

알림 정정 전 일반 통합 회귀는288건 중 실패/오류0·외부 Kafka2건 스킵이었다. 이 수치를 최종 신규 환불 Kafka 통합 결과로 합산하지 않는다. 일반 통합의 기존 EmbeddedKafka 검사와 새 공통 환불 경로의 Kafka 전달 검사는 구분하며 후자는 E에 인계한다. 브라우저 HTML 렌더 확인·실제 Payment/PG·운영은 미실행이다.

- 기본 Java 경로가 없어 최초 Gradle 실행은 비영 종료했다. 이어 새 조회의 RefundWindowPolicy 접근 제한으로 컴파일1회 실패했고 공개 정책 함수로 수정했다. 설치된 Java21의 JAVA_HOME을 명시한 컴파일은 통과했다. 기본 python의 markdown 모듈 누락은 기존 API 가상환경을 사용하여 해결했다.
- Docker Desktop 전용 DB host25432에서 첫 PostgreSQL17건 중4건 실패: JSON 정수 표현을 정규화하지 않은 중복 비교3건과 Outbox spy의 MANDATORY TX 밖 장애 주입1건. 구현/테스트를 수정했다.
- Desktop 재실행은 JDBC COMMIT 응답 read 대기와 docker exec 지연을 확인하고 자기 Test worker PID25113에 SIGTERM을 보냈다. Gradle은 비영 종료했으며 중단·미완료 검사로 기록한다. COMMIT 여부 미확정 DB에서 검사를 겹쳐 재시작하지 않았다. 로그·XML·스택은 로컬 build 인계 evidence에 보존했다.
- 조정자/I가 host25434 전용 Lima PostgreSQL의 인증·COMMIT을 확인한 뒤 fresh schema로 migration부터 다시 실행했다. Lima 첫68건 중1건은 기존 응답유실→PAID 기대와 새 보상 정책이 충돌해 실패했고 회귀 기대를 수정했다. 다음 실행은 PostgreSQL69건과 migration4건이 실패/스킵0으로 통과했다. 이후 승인 송신시작 표시·추가 경합2건·최종 코드의 재검사를 수행한다. 공용 kind/DB/볼륨·전역 Docker context는 변경하지 않았다. 비밀번호는 산출물에 기록하지 않는다.
- 문서 링크 첫 검사는 개편한 Order 문서의 이전 앵커8개가 없어 실패했으며 기존 링크 목적의 절을 복원해 문서266개 오류0을 확인했다.
- Payment/PG 실제 금전 실행·운영 배포는 수행하지 않는다. Kafka/Connect 스택은 공용 자원 압박 지시에 따라 추가 기동하지 않는다.

## E에게 필요한 연결 검증

1. C의 신규 Kafka 요청이 schemaVersion1/UUID/key/orderRef·판매자/항목 수량·합계·요청시각 원문 그대로 D에 영속 접수되는지 확인한다. 사용자와 시스템이 같은 토픽을 사용하고 legacy 요청을 새 요청으로 오인하지 않아야 한다.
2. 승인 HTTP 송신 뒤 응답을 버려 C 보상 fence와 D 승인경합을 검증한다. 정상202·단순조회장애와 구분하며 기존 요청ID로 SUCCEEDED/NO_CHARGE에 수렴해야 한다. 새 approval 재전달0회, 진행 주문은 완료 시 FAILED와 자원1회 해제여야 한다.
3. 실제 D 결과 UNKNOWN→SUCCEEDED 버전2, 실패0원, NO_CHARGE, 중복·역순·원문변조·미등록ID·잘못된key·실행ID 변경을 전달한다. 잘못된 결과/로컬TX 실패는 성공 ack하지 않아야 한다.
4. 사용자 부분수량 순차환불4,000/4,000/2,000과 최종 쿠폰1회 복원, 같은 결제 미완료 충돌, 종료주문 늦은 성공 뒤 자원 추가반환0회를 확인한다.
5. Payment/PG 대역과 실제 공급자 실행을 분리해 기록한다. C PostgreSQL 검사는 Payment HTTP 대역/직접 사실을 사용하며 실제 Payment/PG 연결 증거가 아니다.

## 저장·범위

API 계약은 `contracts/api/commercecore-service.yaml`과 생성 MD/HTML을 변경한다. 이벤트 계약 변경은 상속한 A 묶음이며 C 작성 이벤트 원본 변경은 없다. B 소유 application.yml·공용 outbox·SKU 구현과 Payment/Frontend/Infra 파일은 수정하지 않는다. 최종 인계 patch와 신규파일 목록/해시는 build/core-refund-handoff에 만든다.

## E 전역 문서 정합성 인계

아래 파일·공용색인은 E 소유이며 C가 수정하지 않는다. A 고정 계약 묶음도 보존한다.

| 대상·절 | 현재 잘못되거나 오래된 표현 | 통합 뒤 기록할 동작 | C 코드·검증 근거 |
|---|---|---|---|
| ARCHITECTURE.md, 레이어와 통신 방향 | Core→Payment 결제/환불 모두 내부 HTTP | 승인/준비 HTTP 유지, 신규 주문서 환불만 공통 Kafka 요청/결과. policy0 과거 HTTP 원문은 보존하며 신규 자동 재발행 없음 | KafkaRefundService.request/save, RefundController.request, PaymentRelayDispatcher; KafkaRefundPostgresTest.partialQuantitiesAndCoupon |
| flows/refund.md, 첫 문단·기준 | 현재 환불 전체가 Mock 교체 예정·업무 구현 없음 | C는 신규 주문서 전체/선택/부분수량·Core 결과 반영을 구현. D의 실제 금전 실행과 E의 서비스 연결 증거는 별도. legacy 부분수량은 범위 밖 | RefundSelection, KafkaRefundService, RefundQueryService; partialQuantitiesAndCoupon/fullRemainingAndSerialization |
| ARCHITECTURE.md 외부 복구·Order 후속, 관련 order-payment 흐름 | UNKNOWN/응답 유실도 이전600초 뒤 만료·해제만 설명 | 최초 승인 응답 유실/확인된 UNKNOWN은 즉시 시스템 intent+영속 fence. 정상202 처리중·단순조회실패는 새 보상 아님 | PaymentRelayDispatcher.approval, PaymentRelayService.approvalUnknown; dispatcherRecordsTransportLossOnce/acceptedProcessingIsNotResponseLoss/rejectedHttpIsNotUnknownExecution |
| 위 문서의 승인 경합·전달 경계 | 송신 시작과 보상 접수 순서·fence 누락 | V115 approval_send_started_at 조건부 기록을 그룹 잠금 아래 커밋한 지점이 송신 시작. 보상 선행 시 시작 표시/HTTP 없음. 시작권한 커밋 뒤 진행 호출은 D 보상으로 수렴하며 DB 잠금은 HTTP 동안 유지하지 않음 | PaymentRelayService.claimApprovalSend, V115; compensationDuringLookupBlocksSendClaim/approvalCompensationRace |
| flows/refund.md 전이·상태, ARCHITECTURE.md Order 후속 | 사용자와 시스템 상태 축 미분리, 부분 환불 뒤 묶음 PAID 유지 | 사용자만 CANCEL_PENDING. 성공 누계로 PARTIALLY_REFUNDED/REFUNDED, 확정실패 이전상태 복귀. 시스템은 별도 REQUESTED/UNKNOWN/FAILED/COMPLETED와 outcome | OrderStatus, RefundIntentStore, KafkaRefundService.applyUser; failedRestoresPreviousPartialState/unknownThenSuccessWithReverseDelivery |
| 위 문서의10분 만료·자원 복원 | 모든 미확정 주문은 처리기한에 예약 해제 | 보상 없음: 기존 준비/처리 각600초 만료 유지. 시스템 fence 있음: REQUESTED/UNKNOWN/FAILED 진행 주문 예약 유지. SUCCEEDED/NO_CHARGE 때 FAILED와1회 해제. 이미 EXPIRED/CANCELLED/FAILED면 종료 유지+완료만 기록 | OrderSheetLifecycleService.expire, OrderPaymentResultApplier.completeCompensation, KafkaRefundService.applyLocked; lostApprovalFencesLateSuccessAndExpiry/noChargeAndAlreadyClosed/expiredCompensationDoesNotRestockTwice |
| flows/refund.md 결과 반영·토글 | 항목전체 성공/단일실행토글 중심 | 요청 원문·eventId·executionId·resultVersion 검증, UNKNOWN 예약 유지, 성공 시 이번 수량만 복원·전수량 쿠폰 복원. 새 Kafka Outbox와 legacy execution-enabled 분리 | PaymentEventConsumerConfiguration.CommonRefundResultConsumer, KafkaRefundService; resultCommitFailureIsRetryable/rejectsMismatchedResultTargetsAndStates |
| A states.md/.html의 시스템 완료 표 | OrderFailed 알림 Outbox | 조정 msg_be82ee6c8eb3를 우선하여 시스템 완료 알림 없음. 기존 PaymentFailed 거절/타임아웃 알림 및 사용자 환불 알림은 보존. E가 A 문서와 상위 흐름을 정정 | OrderPaymentResultApplier.completeCompensation; noChargeWithoutPaymentIdDoesNotInventNotification/systemCompletionDoesNotPublishRejectedPayment/normalDeclineAndUserRefundKeepNotifications/userRefundKeepsNotification |

검증 보고의 PostgreSQL 건수는 실제 관계형 DB TX/경합 검사이며 결제 PG 공급자 실행 건수가 아니다. C는 Kafka/Connect 추가 기동과 Payment/실제 PG 실행을 수행하지 않았다. 소유 Order 설계 MD/HTML과 API 원본·생성물은 C 패치에 포함한다.


## 조정 정정·교차검토

- 2026-11-20 조정 결정(msg_be82ee6c8eb3): A states.md의 시스템 완료 시 OrderFailed 알림 지시는 계약 오류다. 시스템 SUCCEEDED/NO_CHARGE는 전용 `completeCompensation`으로 상태/원문/자원만 원자 반영하며 PaymentFailed(REJECTED/TIMEOUT), 존재하지 않는 OrderFailed, 가짜ID/사유를 발행하지 않는다. 정상 결제 거절·사용자 환불 알림은 유지한다. A frozen 원본은 C가 수정하지 않으며 E가 상위 흐름·A 상태 MD/HTML을 정정한다. 별도 시스템 완료 알림 계약은 후속이다.
- D 발견·조정 확인(msg_898a5884714d): RefundRequestId의 원본 계약은 UUID 전용이 아니라 `^[A-Za-z0-9_-]{1,64}$`다. 소비자의 UUID 제한을 계약 패턴으로 고치고 API 조회 스키마의 UUID 가정도 제거했다. 현재 새 요청은 UUID 생성 그대로이며 eventId/executionId UUID 검증은 유지한다. DB 요청 참조는 VARCHAR64로 일치한다.
- `late-7001`/길이1/64 저장 원문 결과·중복 무효과와 0/65/공백/허용외문자/비문자열 거절을 실제 PostgreSQL 결과 반영 테스트에 추가했다. 원결제ID 없는 NO_CHARGE·정상 거절/사용자 환불 알림 보존·시스템 알림0회도 검증한다. 이전71+4 통과는 이 정정 전 결과로 evidence/pre-notification-correction에 별도 보존한다.
