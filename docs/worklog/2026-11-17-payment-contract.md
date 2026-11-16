# 2026-11-17 Payment 계약 호환 검증 (#364)

## 범위와 판단

사용자는 별도 worktree에서 팀원 Payment 계약에 맞추고 이슈·PR·dev 병합까지 진행하도록 요청했다. Orca `payment-contract-alignment`, 브랜치 `test/#364-payment-contract`를 최신 origin/dev `ebea69d6c1e2c43068ec38249f9562a045b3c23d`에서 생성했다. 기존 docs/document-layout 작업과 미커밋 파일은 반입하지 않았다.

팀원 `feat/#349-payment-recovery`의 고정 `800b41fde697c63180a9721fd01da71e1e1b32f0`에서 CreatePaymentRequest·SellerAmountRequest·ApprovalRequest·PaymentController·ApprovalController와 OpenAPI 2.0.0을 읽고 Core와 대조했다. 새 생성·승인 필드와 경로는 이미 일치한다. 기존 dev의 PaymentCommand는 준비·승인 분리 이전 Mock 실행 계약이다. 따라서 이를 기준으로 새 Core를 되돌리는 수정은 하지 않았다. 앞선 로컬 dev만의 비교에서 팀원 브랜치의 실제 구현을 확인하지 않은 진단을 정정한다.

## 변경

HttpPaymentRelayClientTest에서 생성 원문 전체와 판매자 2건을 엄격히 비교한다. checkout 재발급·주문 참조 조회·승인200을 추가하고 202·422·트랜잭션 중 HTTP 금지 검증은 유지했다. 조회의 64비트 ID·금액, UNKNOWN·APPROVING·APPROVED·CANCELED와 승인 시각을 확인한다. 구형 Mock의 raw 실행 응답은 새 준비 성공으로 읽지 않는 경계를 보호한다.

[결제 전달 문서](../design-docs/services/commercecore/states/payment.md)에 두 계약의 구분, 필드·경로 대응, 별도 연결 주소와 토큰 설정, 팀원 PR #352–#359 의존성을 추가했다. HTML은 저장소의 scripts/render_design_docs.py로 생성한다. 운영 Java·DB·API/이벤트 원본과 팀원 Payment 구현은 변경하지 않는다. 취소 전달 #360과 일반 환불은 별도다.

## 실제 검증

- Java: OpenJDK 21, 독립 worktree의 Gradle --no-daemon. HTTP 클라이언트 계약 테스트 11건 실패0·오류0·스킵0.
- 팀원 실제 DTO 검증: git show로 고정800b41fd의 요청 DTO 3개만 /tmp/backend364-provider-dto로 추출했다. Core의 실제 Create·Approval을 ObjectMapper로 직렬화한 후 팀원 DTO로 역직렬화하고 Hibernate Validator 8.0.3.Final로 검증했다. 생성, 판매자2건의 전 필드, 승인, 소수5자리 수수료 거부 경계 4개를 확인했다. 엄격한 역직렬화로 필드 누락·오명명을 확인하며 Instant/OffsetDateTime과 String/UUID가 JSON에서 호환됨을 검증했다. 초기 classpath 추출용 임시 Gradle init script의 project 접근 오류는 rootProject.project로 수정한 뒤 성공했다. 추출 소스·임시 프로그램은 제품 소스나 테스트 리소스에 복사하지 않았다.
- Core 전체 build 성공 뒤 중복 checkout 테스트 1개를 제거하고 최종 단위를 다시 실행했다. 최종 단위183건 실패0·오류0·스킵0, 일반 통합312건 중 실패0·오류0·스킵2. 외부 Kafka 환경 미설정으로 CouponSyncKafkaTest·SalesInfoEventKafkaTest 각1건을 스킵했다. HTTP 계약11건은 최종 단위183건에 포함되며 중복 합산하지 않는다.
- 작업 전용 backend364-postgres(postgres:16, localhost 동적포트50443)의 commercecore_test·payment_test만 사용했다. postgresTest 196건 실패0·오류0·스킵0을 확인했다. 공유 DB·기존 컨테이너는 사용하지 않았다.
- 문서170개 오류0, 설계HTML30개·API HTML6개 최신성 통과, 이벤트 계약8개·예시23개·직렬화표본23개 통과, git diff --check 통과. 이벤트 표본 검사는 실제 브로커 전달 증거가 아니다.
- 저장소 render_design_docs.py가 만든 결제 상태 HTML을 Orca 내장 브라우저에서 확인했다. 새 계약 표·회귀/워크로그 링크를 확인했고 390px에서 문서 가로 넘침이 없었다. UNKNOWN 필터 선택은 4/7 전이로 갱신됐다. 1280px로 복원했다. 첫 필터 조작은 잘못된 heading ref를 지정해 거절됐으며 snapshot으로 실제 combobox를 확인한 뒤 성공했다.

DTO 검증은 실제 Payment 컨트롤러·DB·PG를 기동한 증거가 아니다. 이번에는 팀원 앱·실 PG·실 SDK를 실행하지 않았다. 기존 실제 앱 검증 기록과 이번 DTO/HTTP 계약 대역 결과를 합산하지 않는다. 팀원 PR을 병합하거나 배포한 것이 아니므로 dev의 구형 Mock만 기동하면 새 API 연동은 여전히 불가능하다.

## 저장·후속

이슈 [#364](https://github.com/2026-2-rising-sun/Backend/issues/364), PR [#365](https://github.com/2026-2-rising-sun/Backend/pull/365), base dev / head test/#364-payment-contract다. 게시 직전 최신 사용자 지침의 writing.md 「처음 읽는 사람도 이해할 수 있게 설명하기」「PR 설명 작성과 확인」을 읽었다. 게시 후 제목·본문 전체와 base/head를 조회하고, 기존 계약 호환 확인·회귀 보강이라는 최종 범위, 외부 Kafka2스킵, 팀원 구현 미병합·실PG 미실행 제약을 검토했다. 미해결 리뷰 스레드는 없었다. 최종 CI·병합 여부와 커밋은 GitHub PR의 해당 기록을 기준으로 확인한다.

기존 작업 폴더의 checkout과 미커밋 변경은 보존하며 이 worktree의 파일만 커밋한다. 작업 전용 PostgreSQL 컨테이너는 검증 후 제거한다. 새 Chat 에이전트의 모델·추론 확인 요청에는 아직 답을 받지 않아 추가 에이전트를 실행하지 않았으며, 현재 에이전트가 독립 worktree에서 검증·게시를 진행했다.
