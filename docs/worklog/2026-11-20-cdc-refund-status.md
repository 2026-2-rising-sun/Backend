# CDC 병합 후 현재 흐름과 환불 미병합 상태 정정 (#380)

## 기준과 수정 범위

2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`에서 문서 전용 브랜치를 시작했다. [Backend #379](https://github.com/2026-2-rising-sun/Backend/pull/379)는 head `2c2dde556d93374a0f5294a41e7f9885399d8054`를 squash 병합한 상태다. [Infra #58](https://github.com/2026-2-rising-sun/Infra/pull/58)은 main `cd6778590bcb57eb0603506f2130912e23bd37cc`에 병합됐다. 반면 [Core 환불 #378](https://github.com/2026-2-rising-sun/Backend/pull/378)은 head `228f33faaa5e9519ffd74ec12b2878825fa85dde`의 미병합 Draft다.

아키텍처·Catalog/주문/환불 상위 흐름과 관련 Core/Catalog/Common 모듈의 현재성 설명을 대조했다. 업무 코드·테스트·계약·설정·DB·Payment 소유 문서·AGENTS는 바꾸지 않았다. [B의 이전 기록](2026-11-19-catalog-cdc-sync.md)은 당시 실패·미실행·미커밋 상태를 보존하고 이 정정으로 연결했다.

## 현재 코드로 확인한 것

- 상품·SKU 메타데이터 변경은 같은 업무 TX의 Catalog Outbox다. 재고 변경은 영속 dirty revision이며 기본 60초 fixedDelay 배치가 최신 수량만 합친다. 최대 전파 SLA나 전체 재고의 매분 기록이 아니다.
- Core 일반/claim relay는 Catalog 토픽을 제외한다. Connect의 c/r·정확한 Catalog 토픽 필터가 EventRouter보다 먼저다. 초기 적재는 Core의 명시적 backfill과 Connect의 Outbox initial snapshot을 모두 고려한다. Catalog는 독립 버전·원문 대조·삭제 fence를 유지한다.
- Catalog의 available/status는 표시 정보다. Core 주문 생성에서 실제 재고·가격을 검증한다. Frontend stock/status 버튼 차단 후속은 [전용 인계](2026-11-19-catalog-cdc-frontend-handoff.md)에 남아 있다.
- dev legacy 사용자 환불은 기존 HTTP 내부 API를 호출하고, 새 주문서의 늦은 승인은 구 `commerce.refund-requests.v1` 요청과 `payment.refund-results.v1` 결과 소비를 갖는다. Payment에 실제 환불 provider가 없어 완료 경로를 연결했다고 볼 수 없다.
- 미병합 #378의 사용자 CANCEL_PENDING과 시스템 UNKNOWN/최초 600초→COMPENSATION_PENDING→성공 후 CANCELLED, 공통 dot 토픽은 미병합이다. provider 구 하이픈 결과 계약 불일치와 원요청의 missing paymentId 연결 복구가 실제 차단 사유로 남는다. [현행과 목표](../design-docs/flows/refund.md)를 분리했다.

## E 최종 CDC 단독 실행의 근거

이번 문서 작업에서 업무 런타임을 재실행하지 않았다. E가 만든 불변 receipt·manifest·JUnit 및 테스트 원본을 읽고 파일 해시를 대조했다. 아래는 그 실행 결과의 공개 요약이다. 로컬 build 경로는 팀 공용 자료 링크가 아니며 원시 로그 전체나 자격정보를 저장소에 첨부하지 않았다.

| 항목 | 확인한 조건·결과 |
|---|---|
| 대상 | #379 head의 전체 소스 1,238파일 변경 없음. E 전용 CatalogOnlyRuntimeReviewPostgresTest는 build 사본에만 추가 |
| 실행 | fresh 3차, JUnit 1건·실패/오류/스킵 0, 112.703초, 종료 0 |
| 실제 경로 | Core Spring bean/TX·PostgreSQL → Debezium 3.3.2.Final Connect → 실제 로컬 Kafka → 별도 Catalog bootJar → 공개 HTTP |
| 초기 적재 | 기존 상품·SKU backfill로 기존 사본 재고 1→30 반영 |
| 변경·복구 | 상품명·가격·이미지, 기본 60초 재고 29, 중복/역순, Catalog kill/재시작 뒤 복구, 삭제 뒤 공개 HTTP 404 |
| Payment 경계 | Payment 프로세스·DB·PG 없이 Mockito client 사용, noInteraction 확인. 환불/결제 제공자 실행 증거가 아님 |
| 알림 경계 | 기존 PriceChanged 발행 ACK 2건 관찰. 실제 Notification/Live 소비 앱은 실행하지 않음 |
| 앞선 실패 | 1차는 consumer group 시작 offset 격리 오류, 2차는 누적 로그의 옛 PID를 재시작으로 오인. 각각 JUnit 1건 실패/종료 1을 보존하고 3차 성공에 합산하지 않음 |

증거 식별자: `catalog-only-runtime-0b3bec4a190348a5854595317c462ae1`. manifest SHA-256은 `70fa139602d7c359e43325ddbaf5fa27e3096863dbe963dc49e6c1fec2baee67`, receipt SHA-256은 `e359189bd10c7ab572ab20633d702a1a037cf6a35e74c5afcf45d3658737ab5e`다. manifest에 기록된 18파일은 해시가 일치했다. 이 요약은 E 로컬 실행을 설명하며 이번 문서 검사·CI가 해당 실행을 새로 수행한 것은 아니다.

### 재현 조건

같은 결과를 재현하려면 #379 기준 소스/현재 dev의 업무 코드, Java 21과 위 Infra 구성의 독립 PostgreSQL·Kafka·Connect, Core/Catalog 각각의 고유 schema·consumer group·slot·publication을 준비한다. migration→Catalog 소비 기동→relay 제외 확인→connector 등록→Core 명시적 backfill·상품/SKU 변경→기본 배치 대기→공개 HTTP 대조 순서다. 삭제·중복/역순·Catalog 재시작을 각각 관찰하고 Payment client 호출 0을 별도로 검증한다. connector 권한은 E 테스트 역할을 사용했으므로 최소 권한 검증으로 확대하지 않는다.

E 전용 테스트는 공개 저장소에 포함되지 않아 저장소의 단일 Gradle 명령만으로 이 실행을 그대로 재현할 수 없다. 동일 하네스 재현에는 증거 식별자에 대응하는 테스트 원본과 격리 실행 설정이 추가로 필요하다. 기존 Infra smoke는 [공개 절차](https://github.com/2026-2-rising-sun/Infra/blob/cd6778590bcb57eb0603506f2130912e23bd37cc/local/cdc/README.md)로 재현할 수 있지만 fixture 전송이며 위 앱 실행을 대체하지 않는다.

## 두 JVM 재고 증거의 적용 범위

별도 불변 증거 `rev-inventory-twojvm-7404e5f6cac4c54e`의 manifest SHA-256은 `b2d1aa095bbe3aabad6aa719b3d300cdd9d51d8901a05b83058a65db07a41bc8`다. JUnit 1건·실패/오류/스킵 0이며 두 worker JVM만 배치를 수행하도록 테스트 JVM scheduler를 제외한 상태에서 행 claim·동시 변경·잔여 dirty·재시작을 확인했다. E의 적용성 대조는 #379 대상 77파일 차이 0과 증거 55파일 무결성을 기록한다.

당시 Core JAR와 실행 구성에는 현재 병합하지 않은 환불 코드 및 Payment 기동이 포함됐다. 재고 관련 동일 코드의 해당 단계 근거로만 사용하고, 현재 전체 앱 기동·환불 provider·전체 종단 성공으로 확대하지 않는다. 최종 CDC 단독 실행도 두 Core JVM 단계를 새로 실행하지 않았다.

## 문서 검증과 남은 일

MD 원본을 저장소 `render_design_docs.py`의 공식 render 함수로 해당 HTML만 생성한다. 링크·구조, 설계/API 생성 최신성, 대표 HTML desktop/mobile의 내용·탐색·관계도는 문서 검사 범위다. 업무 코드를 바꾸지 않아 Java 전체 테스트는 반복하지 않는다. 최종 명령과 화면 확인 결과는 다음과 같다.

| 문서 검사 | 결과 |
|---|---|
| scripts/check.sh --base origin/dev --dry-run / 실제 실행 | 문서 전용으로 docs/API 생성/설계 생성 검사 3종 선택, 종료 0. Java·업무 이벤트·외부 실행은 선택하지 않음 |
| scripts/check-docs.sh에 대응하는 check_docs.py | 문서 264개, 링크·구조 오류 0 |
| render_api_docs.py --check | API MD/HTML 10쌍, 불일치 0 |
| render_design_docs.py --check | 설계·기록 MD/HTML 109쌍, 불일치 0 |
| git diff --check | 오류 없음 |
| Orca 내장 브라우저 | Catalog/환불 HTML 1440×1000·390×844, 워크로그 390×844 확인. 페이지 너비 넘침 없음, 관계도 내부 가로 이동·확대/전체 보기·워크로그 링크 확인, 콘솔 오류 없음 |

기존 Python 가상환경을 사용했고 외부 설치는 하지 않았다. 개인 작성 기준을 읽고 저장소 공식 생성기로 HTML을 만들었으며 Orca 내장 브라우저에서 확인했다. 브라우저 확인 중 발견한 #378 문장의 Markdown 제목 오인은 원본을 고쳐 정상 목차·단일 h1을 확인했다. Core SKU V109·Catalog V5 ERD는 독립 버전 설명이 이미 있어 그대로 보존했다.

실제 Payment/PG·Notification/Live 소비 앱·운영 배포·업무 브라우저 E2E는 위 E 실행에서 미실행이다. 문서 HTML 확인은 업무 브라우저 검증과 다르다. provider 구현·계약 합의·paymentId 복구, Frontend 진입 조건, CDC 보관/slot/offset 기반 정리·운영 성능/SLA 검증은 후속이다. #378을 완료 처리하거나 미완료 계획을 완료 폴더로 옮기지 않는다. #380 문서 PR은 독립 리뷰·최신 CI 확인 뒤 조정자가 병합한다.
