# Order 통합 체크포인트 검증

2026-11-13, #331 통합 브랜치 작업 기준. **전체 완료 아님**: SKU의 기존 Sales 어댑터 후속 검증 SHA와 전체 Core 최종 회귀가 남았다. [실행 계획](README.md) · [워크로그](../../../worklog/2026-11-13.md).

## 실행 환경과 재현

JDK21(`/opt/homebrew/opt/openjdk@21`), 전용 PostgreSQL16 임시 컨테이너 `commercecore-order-331-postgres`를 사용했다. 실제 Core/Payment DB는 같은 로컬 PostgreSQL의 서로 다른 임의 스키마/커넥션이며 HTTP로 통신한다. 결제 제공자는 DurableMockGateway/RefundGateway다. 실제 PG 제공자·AWS·브로커·원격 CI 검증은 아니다.

`COMMERCECORE_TEST_POSTGRES_URL/USER/PASSWORD`와 `PAYMENT_TEST_POSTGRES_URL/USER/PASSWORD`가 PostgreSQL 검사에 필요하다. H2 검사에는 이 환경변수를 넣지 않는다. 기존 PaymentDatabaseTestSupport는 Core URL이 있으면 H2 대신 별도 PostgreSQL 스키마를 사용한다. 스키마 이름은 프로세스별 UUID이며 테스트는 순차 실행했다. 다른 작업의 DB/컨테이너를 정리하지 않는다.

| 명령·대상 | 실제 결과 |
|---|---|
| Core `test` |169건, 실패/오류/스킵0 |
| Core `postgresTest --tests '*OrderSheet*PostgresTest' --tests '*OrderLegacySkuPostgresTest'` |26건, 실패/오류/스킵0. 주문서16·종료/보상6·구 API4 |
| Core `migrationTest --tests '*OrderSheetMigrationPostgresTest'` |2건, 실패/오류/스킵0. V100 데이터→V104 보존 |
| Core `integrationTest` 선택: PaymentGroupIntegrationTest/CartCouponPreviewApiTest/OrderCreationApiTest/OrderCheckoutApiTest/CartOrderIntegrationTest |64건, 실패/오류/스킵0. 실제 Postgres 환경변수 아래 PaymentGroup은PG, 나머지명시H2를 사용; 이전58건은H2만별도실행 |
| Core `postgresTest --tests '*Refund*PostgresTest' --tests '*PaymentDeliveryBoundaryPostgresTest'` |53건, 실패/오류/스킵0. 기존 환불 성공·실패·UNKNOWN·재시도·롤백·중복/일반flag off·수신경계 |
| Payment `test/integrationTest/postgresTest/migrationTest` 초기 엔진 체크포인트 |8/3/41/6, 실패/오류/스킵0. 이 검사는 이후 발견한 종료HTTP오류를 검증한 결과가 아님 |
| Payment `integrationTest --tests '*PaymentSecurityIntegrationTest'` 후속 |8건, 실패/오류/스킵0. 정확 서비스 인증·잘못된/없는 토큰·사용자Bearer 거절, null/blank/비ISO/숫자시각400 |
| Core/Payment `spotlessCheck` |통과 |
| API 구조 | Core64·전체129매핑 일치, 실제 DTO/상태/쿠폰/수량환불 및 승인 Product3API/Cart CRUD 조각 통합 |
| 문서·생성 최신성 |문서134 오류0, API HTML6/설계 HTML28 불일치0 |
| 이벤트 정적/표본 |계약7·예시22·직렬화 표본24 검사 통과. 표본은 테스트 산출물이며 브로커 전달 아님 |

검사 로그는 작업 폴더의 `build/design-handoff/Order-review-checkpoint.log`, `Order-final-owned-checkpoint.log`, `Order-legacy-refund-postgres.log`, `Order-HTTP-checkpoint.log`와 각 Gradle XML에 있다. 원시 로그는 재현용 로컬 자료이며 본 문서의 검증 범위를 넘어 성공으로 해석하지 않는다.

## 발견·수정·재검사

| 최초 실패 | 원인·수정 | 수정 후 |
|---|---|---|
| 주문서PG15/15 | 새 SKU fixture의 snapshot_version=0이 양수 제약 위반.1로 고침. V102 이행 값 보존은 별도 migration 검사 |26개 전체·보안8 최종 통과 |
| 주문서/환불8건 | 직접 구매도 빈 Cart 완료 포트를 호출. Cart 출처가 있을 때만 호출 |26개 전체·보안8 최종 통과 |
| 종료 HTTP403 | Payment 인증 경로 matcher 누락. 정확 `/payments/*/order-closure`의 SERVICE_commercecore 허용 추가 |26개 전체·보안8 최종 통과 |
| 종료 HTTP400 | 기본 RestClient가 Instant를 epoch숫자로 직렬화했지만 수신 JsonTypePolicy는 ISO문자열만 허용. Core DTO는Instant를 유지하고 @JsonFormat STRING만 지정 |26개 전체·보안8 최종 통과 |
| 비동기 보상 테스트 경합 | 별도 엔진/reconciler가 유효 보상을 먼저claim할 수 있어 확정 결과를 제한시간 안에 조회 후 Core 멱등 반영 확인 |26개 전체·보안8 최종 통과 |
| 기존 환불PG53 중5실패 |옛 Sales 재고 조회·초과 복구 실패 주입을 신규 SKU 원본으로 전환. 기대 수량/롤백/재실행 의미와값은 유지 |53/53 통과 |

위 처음 네 실패·직접 구매 실패 수정은 최신26개 전체/인증8 검사에서 재검증했다. 실패 이력을 삭제하거나 첫 검사부터 성공했다고 기록하지 않는다.

## 전체 Core 회귀 보류와 실패 분류

Cart 최초 기준은 H2 단위165 통과/통합298 중30실패·외부Kafka2스킵이었다. Order 전환 뒤 첫 전체 검사는298 중53실패·2스킵이었다. 같은 업무 코드의 단순 악화 건수로만 비교할 수 없다: 신규 Product 실행 잠금·SKU 원본 적용과 실제PG 환경변수 주입이 함께 바뀌었다.

| 첫 전체 실패군 | 건수 | 원인·조치·후속 결과 |
|---|---:|---|
| PaymentGroupIntegrationTest |27| H2 MERGE fixture가 PG에서도 실행됨. portable INSERT/기존행 명시 갱신으로 변경하고64개 선택 회귀에 포함해 통과 |
| OrderCreationConstraintIntegrationTest |2| 첫 주문 생성 후에 Product/SKU fixture를 준비한 순서 오류. 생성 전에 준비,106개 실패군 재실행에서 해소 |
| CartOrderIntegrationTest |3| Product stub만 있고 잠글 실제 Product가 없음. Cart c37c20af 실제행 보완 후 추가로 드러난 SKU 조회/상태 fixture4건은 d85332a9로 수정, 최종6건 통과 |
| OrderCreationConcurrencyTest |3| 품절 assertion이 옛 Sales 상태를 읽음. 같은 재고0의 SKU 파생SOLD_OUT을 검증, 실패군 재실행에서 해소 |
| CommerceCoreStateTransitionTest |11| Product 실물/신규SKU fixture 보완 뒤 기존Sales 상태/증감 API 원본에 의존하는8건이 남음. SOLD_OUT fixture는 물리ON_SALE+available0으로 표현. SKU 후속 통합 뒤 재실행 필요 |
| PaymentDelayReconcilerTest |1| 실패 주입이 옛 Sales 재고에 적용됨. SKU 원본으로 변경하고 추가 fixture만 보강, 실패군 재실행에서 해소 |
| MemberCommerceCoreApiTest |3| 오버플로 검사 가격을 옛 Sales에만 변경함. SKU 가격으로 변경, 실패군 재실행에서 해소 |
| SalesInfoEventIntegrationTest |3| SKU 소유 기존 등록/이벤트 어댑터 후속 미통합. 소유자에게 전달, Order가 해당 모듈을 대신 수정하지 않음 |

실패군106 재실행은94통과/12실패였고 그 중Cart4는후속6건검사에서통과했다. 남은Sales관련검사를통과로세지않는다. SKU후속이 기존채팅의 실행대기카드에 머문 사실을 코디네이터가 확인했다. 검증된 SHA 수신 뒤 같은 ancestry로 merge하고 최종 통합HEAD에서 전체Core를1회 재실행한다.

## HTML 실제 브라우저

기존 FigJam 색상으로 ERD/상태 HTML을 MD와 대조했다. 같은 이름 MD 원본 링크를 실제 클릭·뒤로가기로 확인했고1280/390px에서 모든SVG의fit/100%/확대/축소·가로탐색과page overflow없음을확인했다. 네 화면의console오류는0이었다. ResizeObserver는 폭 변경만 감지하고requestAnimationFrame으로 렌더하여 기존루프오류가재발하지않았다.

`build/design-handoff/Order-final-browser-check.json`에행동·상태·스크린샷경로가있다. ERD 전체와상태모바일을실제이미지로확인했다. SVG별 geometry 검사3개 모두0finding이다. diagram-design의전체self_check는임의스크립트를motion controller로간주하여pan/zoom스크립트와motion-root부재를거절했다. 애니메이션을추가하거나검사기를개편하지않았으며이검사를통과로기록하지않는다;브라우저상호작용검증과기하검사를별도증거로유지한다.

## 남은 한계

전체Core최종회귀·최종공유SKU계약/어댑터연결·root하네스완료는미실행이다. 외부Kafka2스킵, 실제Redis/PG제공자/AWS/원격CI는미검증이다. 옵션JSON공급과공개다중SKU표시정책은보류범위다. 계획은active로유지하고PR도통합검증전에는Draft로표시한다.

실제 브라우저 캡처: [1280px ERD](../../../design-docs/commercecore/order/assets/order-erd-desktop.png) · [390px 상태/환불](../../../design-docs/commercecore/order/assets/order-state-mobile.png). 확대 상호작용 결과는 위 로컬 JSON에 별도 기록했다.
