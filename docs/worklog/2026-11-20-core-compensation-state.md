# Core 보상 대기·취소 확정 정정

## 범위와 기준

- 작업: task_196cba3adf7b, revision `core-compensation-state-r1`.
- 시작/종료 branch `core-refunds`, HEAD `3537340c4686fec3b962da8ed02d368829d639af`. 기존 미커밋 Core/A 변경을 보존하고 이번 시작점 대비 증분만 인계한다. 커밋·푸시·PR·이슈·배포 작업은 하지 않는다.
- 최신 사용자 승인에 따라 이전 일일 기록의 즉시 통신유실 보상·PAYMENT_CONFIRMING 유지·완료 FAILED 정책을 정정한다. 이전 frozen 인계물은 역사적 증거이며 수정하지 않는다.
- CommerceCore 요청 검증·상태/원문/Outbox 저장·결과 소비/자원 반영만 구현한다. Payment의 consumer·PG 환불·조회/대사·결과 producer는 타팀 책임이며 수정·실행하지 않았다. 이전 D/Lookup 산출물을 적용하지 않는다.

## 확정 상태표

| 현재 상태·입력 | Core 주문 그룹·항목 | 의도·자원 |
|---|---|---|
| PENDING_PAYMENT의 결제 전 생성 기한 도달 | 기존 EXPIRED | 기존 예약 해제·만료 알림 유지 |
| PAYMENT_CONFIRMING, 시작+599초 무응답·통신오류 | PROCESSING 유지 | 보상 요청0, 예약 유지 |
| PAYMENT_CONFIRMING, 첫 시작+600초 무응답 | COMPENSATION_PENDING | 같은 TX에서 고정ID·전체 원문 SYSTEM_COMPENSATION·Outbox1, 예약 유지 |
| PAYMENT_CONFIRMING, 명시적 승인 UNKNOWN | 즉시 COMPENSATION_PENDING | 동일한 보상 TX, 중복 진입은 기존 요청 유지 |
| 정상202 PROCESSING | PROCESSING 유지 | UNKNOWN으로 추정하지 않음, 첫 처리 기한 연장 없음 |
| COMPENSATION_PENDING 뒤 늦은 승인 | 대기 유지 | 금전 사실만 저장, 정상 PAID 복귀·추가 승인 송신 금지 |
| 검증된 결과 UNKNOWN/FAILED | 대기 유지 | 자원 보존, 결과 버전·원문·실행ID 저장 |
| 같은 요청·실행의 상위버전 SUCCEEDED/유효 NO_CHARGE | CANCELLED | 그룹·항목·완료 원장·예약 해제를 한 TX에 확정, 중복/역순 추가 반환0 |
| 이미 EXPIRED/CANCELLED/FAILED인 과거 주문의 보상 완료 | 종료 상태 유지 | 완료만 기록, 재활성화·추가 자원 반환 없음 |
| 정상 사용자 환불 | 기존 CANCEL_PENDING→PARTIALLY_REFUNDED/REFUNDED | 이전 부분수량·금액·알림·실패 복원 정책 유지 |

보상 실패는 완료가 아니다. 기존 terminal-intent guard를 시스템 FAILED에 한해 해제했으며 새 요청ID나 실행ID를 만들지 않는다. 동일버전 다른 원문, 실행ID 변경, 완료 뒤 더 높은 모순 결과는 계속 거절한다. 이미 검증한 낮은 FAILED/UNKNOWN 재전달은 무효과로 기억한다. 완료 시 PG 거절을 뜻하는 PaymentFailed나 가짜 알림을 합성하지 않는다.

## 호출 경로·원자성

`PaymentRelayDispatcher`의 승인 HTTP 통신 예외/의미 불명 HTTP 실패는 pending 전달 이력만 남긴다. `OrderSheetLifecycleService.expire`는 신규 주문서의 PAYMENT_CONFIRMING 처리 기한에서 일반 만료 대신 `KafkaRefundService.compensateLocked`로 진입한다. 명시적 UNKNOWN도 같은 경로로 들어간다. 구매 guard→그룹 잠금 아래 상태·항목·고정 원문·Outbox를 커밋하며 외부 HTTP 동안 DB 잠금을 유지하지 않는다. 기존 `approval_send_started_at` 송신 시작 fence와 영속 보상 fence를 유지한다.

`OrderPaymentResultApplier.completeCompensation`은 COMPENSATION_PENDING에서 CANCELLED로만 확정한다. 일반 결제 거절의 FAILED/알림과 분리한다. 보상 대기는 활성 조회와 새 구매 차단에도 포함하며 API 그룹/항목 enum에 노출한다. Frontend는 새 COMPENSATION_PENDING을 처리 중 보상 상태로 표시해야 하며 Frontend 파일은 수정하지 않았다.

V115는 변경하지 않았다. 새 V116은 CHECK·활성 회원 유일 인덱스를 확장하고, 기존 미완료 보상을 가진 진행 중 policy1 주문만 원문·ID·버전·재고 예약을 보존하여 대기로 이행한다. 이미 종료한 주문은 유지한다.

## 공통 토픽과 소비 검증 경계

사용자/시스템 요청은 모두 `commerce.refund.requests.v1`, 결과는 모두 `payment.refund.results.v1`이다. 두 토픽의 key는 처음부터 존재하는 orderRef(groupNumber)로 같으며 payload purpose만 신뢰하지 않고 refundRequestId로 찾은 저장 원문의 목적·대상·금액·수량·시각·ID를 대조한다. Core 생산 요청 계약의 필드 의미 변경은 없어 이번 증분에서는 보존했다. Payment 담당 결과/실행 계약은 수정하지 않았다. 테스트 결과 형태는 Core 소비자가 기대하는 fixture이며 상대 producer 구현의 증거가 아니다. 기존 legacy 호환 소비 경로는 변경하지 않았다.

RECORD ack·무한 backoff 오류 처리를 유지한다. 불일치/미등록 결과나 로컬 반영 장애는 예외로 전파하여 성공 ack하지 않고 결과 원장을 확정하지 않는다. broker 재전달·실제 offset은 이번에 실행하지 않았다.

원요청에 paymentId가 없고 결과가 새 ID만 제공하면 기존 검증된 binding이 없는 한 거절·보류한다. 현재 Payment GET 필드만으로 구매자·판매자 원문을 안전하게 확인할 수 없으므로 연결 복구 결함은 미해결이다. 새 필드를 Mock에 만들거나 이벤트 ID를 신뢰해 연결하지 않았다. 진짜 ID 없는 NO_CHARGE 완료는 계속 지원한다.

## 실제 검증

Java21, 기존 C 전용 PostgreSQL `127.0.0.1:25434/core_refunds_c`의 매번 분리된 schema를 사용했다. DB/user를 실제 SQL로 대조하고 테스트 schema만 정리했다. 비밀번호는 프로세스 환경으로만 전달했고 문서/인계물에는 저장하지 않는다. Docker 명령·공용 컨테이너·새 스택·다른 서비스 프로세스를 사용하지 않았다.

| 검사 | 실제 결과 |
|---|---|
| Core test | 203건, 실패/오류/스킵0 |
| Core 일반 integrationTest | 288건 중 외부 Kafka2건 스킵, 실패/오류0, 실제 실행286건 |
| Core postgresTest 최종 업무 소스 | 96건(환불52·중계39·도메인5), 실패/오류/스킵0 |
| Core migrationTest 최종 강화본 | 5건(V115→V1161·환불schema4), 실패/오류/스킵0 |
| spotlessCheck | 통과 |
| API 구조 검사 | Core66 포함128 매핑 일치, 서버 동작 증거 아님 |
| 이벤트 원본 정적 검사 | 9계약·29예시, 직렬화 표본0인 기본 모드임 |
| Core 실제 Outbox 직렬화 요청 표본 | 사용자1·시스템1을 Core 소유 요청 계약으로 검증, broker/Payment 실행 아님 |

단위·일반 통합 검사 이후 수정한 것은 PostgreSQL 전용 회귀 기대/fixture와 migration fixture다. 업무 소스는 동일하며 수정된 PostgreSQL 검사는 최종 재실행했다. 마지막 migration 검사는 자동생성 PK 충돌로 인덱스 검증이 우연히 통과하지 않게 독립ID와 정확한 인덱스명까지 대조했다.

명령과 exit/XML/소스hash는 `build/core-compensation-state-handoff`에 기록한다. 초기 환불 PostgreSQL49건 통과, 전체 회귀의 PostgreSQL95건 중 이전 정책 기대2건 실패(exit1), 다음 업무 검사96건 통과 뒤 migration fixture의 삭제된 lookup_password_hash 컬럼 사용1건 실패(exit1)를 각각 보존했다. fixture를 실제 V115 스키마에 맞춘 뒤96+5 통과했고 인덱스 검증 강화 후 migration5를 다시 통과했다. 첫 기본 Python의 verify dry-run은 tree_sitter_java 미설치로 exit1이었고 기존 API venv로 재실행했다. dry-run의 전역 build/타서비스 검사는 현재 허용 범위 밖이므로 실행하지 않고 Core 검사를 직접 선택했다.

## 미실행과 남은 문제

Payment 실제 실행/PG·Toss·조회 대사·결과 producer, 실제 서비스 연결, 공통 환불 Kafka 전달/offset 재전달, 외부 Kafka2검사는 미실행/스킵이며 성공으로 보고하지 않는다. missing-paymentId binding 복구, 별도 시스템 완료 알림, 타팀의 결과 생산 계약·구현은 후속이다. 기존 policy0 legacy 흐름을 새 시스템 보상 흐름으로 전면 이행하지 않는다.

시작점 전체1242파일 중 허용 밖841파일의 hash와 status를 종료 시 대조하고 Payment 경로는 baseline clean임을 확인한다. 원래 A/frozenC/중지된 binding/다른 공간 산출물은 적용하거나 수정하지 않는다. 이번 증분만 별도 patch·신규 archive·before/after hash·receipt로 전달하며 전체 작업은 미커밋 상태다.

## Draft PR 준비 검증

기준 dev3537340c와 선별된 Core 파일만으로 독립 사본을 만들어 Core 단위와 통합 테스트 컴파일을 다시 검증했다. 기존 업무 소스 해시는 최종203/288(외부Kafka2스킵)/PostgreSQL96/migration5 증거와 일치한다. Payment baseline은 구 payment.refund-results.v1의 RefundExecutionResolved이므로 Core가 기대하는 새 dot 토픽 결과는 미확정 연동 게이트다. Payment 생산 계약은 수정하지 않는다. 최초 게시 당시 전역 API 생성 최신성은 Core 계약을 참조하는 Live 생성문서2개가 stale이었다. 후속 문서 승인으로 그2개만 입력 SHA 재생성하여 현재10쌍 최신성은 통과한다. Core 문서의 별도 상속 계획 링크는 최신 Core 워크로그로 정정했다. 커밋·push·Draft PR 생성은 후속 사용자 승인 범위이며 병합·배포는 수행하지 않는다.

## 병합 준비 엄격 검토·회귀 수정

PR378의 최초 head80c13632를 다시 검토하여 공유 만료 서비스가 policy0에도 적용됨을 확인했다. PAYMENT_CONFIRMING 전체를 새 보상으로 보내던 분기는 기존 policy0의 relay.binding이 없어 NPE를 발생시켰다. 기존 OrderProcessingDeadlinePostgresTest9건 중5건 실패(exit1)를 실제 C 전용 PostgreSQL 독립schema/Core 외부Mock으로 재현했다. 신규 보상 분기를 isOrderSheet(policy1)로 한정하여 기존 policy0의 EXPIRED·자원해제·늦은승인 필요사실 기록을 복원하고, 기존 처리기한 경계에서 common intent/Outbox0도 검증했다. 수정 뒤 단위203, 일반통합288(실제286·외부Kafka2스킵), PostgreSQL105(legacy9+신규96), spotless를 재실행하여 실패0/exit0을 확인했다. V115/V116은 변경하지 않았다. Live 문서2개는 dirty before를 보존했으며 기존내용/HEAD내용과 비교해 입력 SHA64자리 외 차이가 없음을 검증했다.

현재 기본 설정의 refunds.api-enabled=true에서 신규 policy1 사용자 환불은 접수되면 CANCEL_PENDING이 되고, 시스템 UNKNOWN/600초는 그API 토글과 무관하게 COMPENSATION_PENDING으로 진입한다. 그러나 Payment는 새 요청 consumer/새 결과 producer를 제공하지 않고 provider 원본도 구 하이픈 토픽/RefundExecutionResolved다. 이 상태의 기본 활성 흐름은 완료되지 않는다. 사용자 환불은 대상금액/수량을, 시스템은 재고·쿠폰 예약과 활성 구매 자리를 계속 점유한다. 이는 실제 병합 차단이며 Draft나 CI 통과로 해소되지 않는다. 구 legacy 환불 HTTP/하이픈 결과 소비와 정상 결제 성공 경로는 유지하고 신규 결과를 구 결과로 변환하여 검증을 완화하지 않았다.

missing-paymentId는 새 사용자 접수와 정상 승인 진입 모두 저장binding을 요구하므로 정상 신규 API 경로의 보편적 오류는 아니다. 다만 과거/불완전 연결에서 원요청에 원결제ID가 없고 결과에 새ID가 있으면 동일 결과를 계속 거절하고 결과원장을 확정하지 않는다. 이미 송신 중이던 정상 PREPARE 응답이 뒤늦게 와 기존binding을 채우면 코드상 재처리 가능하지만, 응답을 영구 유실한 경우 새 PREPARE는 대기/종료 상태에서 차단되고 현재 GET6필드만으로 구매자·판매자 원문을 확인할 수 없어 자동수렴을 보장할 수 없다. ID없는 유효 NO_CHARGE는 기존 테스트로 완료 가능하다. 실제 Kafka에서의 정체 범위는 미실행이며 정적 무한재시도 설정상 같은 partition의 후속 소비 지연도 가능하다.

Core opt-in(default-off) 격리는 대안분석만 했고 구현하지 않았다. 기존 api-enabled만 끄면 시스템 자동보상을 막지 못한다. 별도 승인된 정책으로 신규 policy1 환불을 접수 전 거절하고, 기존intent없는 빠른UNKNOWN은 Processing·예약을 유지하고,600초는 baseline EXPIRED로 분기해야 한다. 종료 뒤 늦은 승인의 기존 payment_relay_refund/하이픈4필드 발행까지 보존해야 하며 이미 접수된 common intent/fence/결과 소비는 OFF에도 유지해야 한다. 새 기능의 정상600초보상 요구는 ON일 때만 적용되는 별도 롤아웃 결정이므로 사용자 승인·on/off·이행 회귀 없이 토글을 추가하지 않는다.

이행 테스트의 로컬 DB명/계정 core_refunds_c 고정 assertion도 CI의 commercecore_test/postgres와 불일치함을 확인했다. 접속 계정 이름은 migration의 업무 조건이 아니므로 테스트 전용 schema 일치 검증으로 바꾸고 최종 migration5를 C 전용 DB에서 재실행했다. CI DB를 로컬에서 기동하거나 연결한 것으로 보고하지 않는다.
