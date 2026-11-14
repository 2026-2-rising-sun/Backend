# Order 최종 통합 검증

2026-11-14. 코드 기준 `a68c31dabc1ae024d5b22c695a2fe42aa61a7dde`, base `cc91c09b753a12a352bda65481210e264d3e88b0`.

**코드·로컬 검증 및 코드 HEAD CI는 통과했다. 사용자 영향 세 항목은 확인 대기이므로 PR은 Draft, 실행 계획은 active다.** [계획](README.md) · [워크로그](../../../worklog/2026-11-14.md) · [직접 합의와 구분하는 구현 선택](../../../design-docs/commercecore/order/설계.md#8-직접-합의와-구분하는-구현-선택).

## 저장·선행·범위

Order 브랜치는 `refactor/#331-order-module`, [PR #343](https://github.com/2026-2-rising-sun/Backend/pull/343)의 base는 Cart다. `ddf4d083`은 Order `0f59e17c`와 Cart `6f9dda99b5ff92acf739729ec8941c0adcf404ad`를 일반 merge했다. Product `729d2d5f`, SKU `1a09aacd`/문서 `c0f3a52c` ancestry를 보존했다. dev 병합·배포는 하지 않았다.

공통 Core YAML에 Product/SKU/Cart 실제 API 의미와 오류를 통합하고 최소 Payment 종료/보상 계약도 유지했다. 기존 PK·물리명·과거 금액·Cart ID/version·TTL을 보존한다. Sales 이력 없는 SKU도 구 API에서 생성·결제·취소·환불하며 신규 구 API 항목은 sku_id/product snapshot을 기록한다. 옵션 JSON 공급·공개 다중 SKU/대표가·가격 정렬은 보류다.

## 실제 명령과 환경

`JDK21`, 전용 PostgreSQL16의 Core/Catalog/Payment/Notification/Live 별도 DB와 테스트별 스키마, 전용 Kafka4의 내부/외부 분리 리스너를 사용했다. 결제는 별도 Payment 컨텍스트·DataSource와 실제 HTTP를 연결한 Mock gateway다. 일반 통합은 명시 H2 fixture와 환경 지원 PostgreSQL fixture가 섞여 있으므로 전부 H2 또는 전부 PostgreSQL로 부르지 않는다.

```bash
scripts/check.sh --base origin/dev --external
```

서비스별 `*_TEST_POSTGRES_URL/USER/PASSWORD`, Notification의 `NOTIFICATION_TEST_DB_DRIVER/USER/PASSWORD`, `LIVE_PG_TEST/PORT/USER/PASSWORD`, `COMMERCECORE_TEST_KAFKA_SERVERS`를 전용 자원에 설정했다. 테스트 결과 재사용을 피하도록 cleanTest/cleanIntegrationTest/cleanPostgresTest/cleanMigrationTest 뒤 빌드 캐시를 끄고 실행했다. 실제 결과는 `passed`, 검사 중 diff 변경은 false다.

| 서비스 | 단위 | 일반 통합 | PostgreSQL 업무 전용 | 이행 전용 |
|---|---:|---:|---:|---:|
| common | 61 | 60 | 선택 없음 | 선택 없음 |
| Catalog | 12 | 99 | 4 | 3 |
| CommerceCore | 169 | 309 | 162 | 23 |
| Live | 38 | 216 | 5 | 선택 없음 |
| Member | NO-SOURCE | 32 | 선택 없음 | 선택 없음 |
| Notification | NO-SOURCE | 106 | 일반 통합에 조건부3 포함 | 선택 없음 |
| Payment | 8 | 8 | 41 | 6 |

고유 Gradle task 기준 **1362건, 실패0·오류0·스킵0**이다. 별도 PostgreSQL 업무·이행 작업은244건이다. NO-SOURCE 단위 두 개와 선택되지 않은 작업은 테스트 통과 건수에 넣지 않았다. 같은 task가 로그에 반복 출력된 것을 중복 합산하지 않았다.

문서 링크137, API HTML6·설계 HTML28 최신성, API 매핑, 선택기/하네스 검사, 이벤트7계약·22예시·현재 직렬화29표본도 통과했다. 표본은 브로커 전달과 구분한다.

## Order·선행 실패 수용 기준

| 대상 | 최종 확인 |
|---|---|
| CartOrder4·PaymentGroup27·CartCouponPreview1 | 기존 Cart CI43584648800의 실패는 최종 Core309 전체 성공에 포함해 해소 확인 |
| SalesInfoEvent1 및 Order 후속 이벤트·상태·권한 | 실제 Product 소유/SKU 원본 fixture로 보완 후 Core309·실제PG162 전체 성공 |
| 주문서 생성·서버 스냅샷·선점·10분 | OrderSheet PostgreSQL16건에 포함. 신규 버전1/UNKNOWN 기한 유지, 멱등성·경합·원자성 |
| 종료·늦은 성공·재고 재복구 금지 | 보상 PostgreSQL8건. 취소 뒤 실행 없음·확정FAILED 뒤 상충SUCCESS 거절 포함 |
| 구 API/Sales 이력 없는 SKU | 실제PG7건. 생성/재요청/취소/결제/환불 성공·중복, 복수SKU 거절·과거 재고 불변 |
| 부분 수량 환불·쿠폰 복원 | 주문서16건과 기존 환불 실제PG 회귀에 포함. 4000/4000/2000, 0원 잔여 수량, 누적 전량·원래 유효기간·후속 사용 보호 |
| V100→V104 | 이행23건 중 Order2건. 과거 PK/금액/Cart 출처/15분 TTL·이행 원자성 |
| 외부 Kafka2 | Sales API→SKU/Outbox→실제 브로커→테스트 소비자의7 이벤트와 쿠폰 Outbox/도메인 JSON 수신. 키·헤더·봉투·버전 확인 |
| 영향 소비자 | Catalog99·PostgreSQL4·이행3, Live216·PostgreSQL5, Payment8/8/41/6 회귀. Catalog Kafka 검사는 EmbeddedKafka로 구분 |

실제 외부 PG 제공자, AWS/운영 환경, 실제 Redis, 배포 검증은 하지 않았다. 외부 Kafka 테스트의 테스트 소비자를 별도 배포된 Catalog 소비자/DB 종단 증거라고 주장하지 않는다.

## 실패·수정 이력

- Cart의33실패와 Order의299/12실패/2스킵을 보존한 뒤 통합했다. 첫 `0395de31` 하네스는 Core309/12실패, 실제PG162/3실패로 실패했다. Product 중복·옛 Sales 변경·판매자 없는 stub 오염을 고쳤고 기대 권한·수량·가격·이벤트 개수/버전·롤백을 유지했다.
- `19fbb7c4` 전체 빌드와 PostgreSQL244건은 실패0이었지만 Notification3스킵 때문에 하네스는 incomplete였다. 전용 Notification PostgreSQL 환경으로 해당3건을 실제 실행했다.
- CI43585905802는 환불 작업자 처리 수1/실제2로 실패했다. Payment 확정과 Core 반영을 다른 작업자가 각각 세는 테스트 드라이버를 Core 반영 기준으로 수정했다. 관련 실제PG15건과 최종 전체 회귀가 통과했으며 원래 동시성·누적금액20000·Mock 결과1건 검증을 유지했다.
- 첫 Kafka 준비/내부 주소 오류, 부분 메타데이터로 구독 토픽 누락, V104 fixture/HTTP403/ISO시각400 등 이전 실패와 수정 근거는13일·14일 워크로그에 보존한다. 실패·스킵·미실행을 처음부터 통과한 것으로 바꾸지 않는다.

## CI·브라우저·남은 확인

코드 HEAD `a68c31da`의 [CI43586694261](https://github.com/2026-2-rising-sun/Backend/actions/runs/43586694261)는 SUCCESS다. 원격 업로드 XML은726건 실패0·오류0·외부Kafka2스킵이다. artifact의 SHA는 PR 테스트 merge 커밋이며 워크플로 head_sha는 a68c31da다. 로컬의 스킵0과 구분하며 업로드되지 않은 작업을 XML 합계에 보태지 않는다. 이후 문서만 갱신한 최종 HEAD의 CI는 PR 체크와 최종 인계 보고에서 확인한다.

Order ERD/상태 HTML의1280·390px, fit/100%/확대·축소·내부 가로 이동, MD 링크/복귀, 페이지 overflow와 콘솔을 실제 Orca browser로 확인했다. 공통 ERD의 Cart select 폭 CSS도 보존한다. [ERD 캡처](../../../design-docs/commercecore/order/assets/order-erd-desktop.png) · [상태 모바일](../../../design-docs/commercecore/order/assets/order-state-mobile.png).

확인 대기 세 항목은 **구 API 신규 주문15분**, **미확정 환불1건 제한**, **일반 환불flag-off의 COMPENSATION 예외**다. 직접 사용자 선택 근거는 없으며 현재 동작·영향·대안을 상세설계8절에 명시했다. 답을 받은 것으로 간주하지 않고 임의 변경하지 않는다. 이 때문에 정책 전체 완료를 선언하거나 계획을 completed로 옮기지 않는다.
