# #331 Order 상세설계와 구현 실행 계획

상태: 구현 진행 중 — 공통 착수 지시 수신. Product→SKU→Cart 선행 통합 대기. U1/U2/U3 모두 확정돼 코드·계약·검증을 진행한다.

[이슈 #331](https://github.com/2026-2-rising-sun/Backend/issues/331) · [ADR 0331](../../../adr/0331-order-snapshot-lifecycle.md) · [상세설계](../../../design-docs/commercecore/order/설계.md) · [ERD 설명](../../../design-docs/commercecore/order/ERD.md) · [상태 설명](../../../design-docs/commercecore/order/상태관리도.md) · [워크로그](../../../worklog/2026-11-13.md#331-order-설계-통합-검토-준비)

## 목표와 권한

주문서 생성 때 한 주문번호와 SKU 항목을 저장하고 당시 가격·표시 정보를 보존한다. 10분 기한, 결제/환불 결과 적용, SKU 선점·해제/복구가 중복과 경합에도 맞도록 설계·구현·검증하는 것이 최종 목표다. 현재 단계는 확정 범위 코드·계약·테스트·MD/HTML 구현이다. 과거의 구현 착수 대기는 2026-11-13 공통 착수 지시로 해제됐다.

- 최종 PR은 Order #331, Cart #332, Product #333, SKU #334의 네 개다. Order 작업을 새로운 Payment/Inventory PR로 분리한다고 가정하지 않는다.
- 코디네이터의 네 설계 검토와 명시적인 구현 착수 메시지 전에는 코드·DDL·계약 구현·커밋·푸시·PR을 시작하지 않는다.
- 구현 전환 시 코디네이터가 기존 세션에 `gpt-6.1-sol / medium`을 명시 적용하고 재조회한다. 현재 세션 모델·전역 설정을 스스로 바꾸지 않는다.
- 구현 단계의 FigJam 수정은 코디네이터가 맡는다. Order는 변경 근거와 node-id를 전달하고 같은 영역을 동시에 편집하지 않는다.
- 독립 Payment 서비스의 신규 종료 수신 기능은 필요성이 있지만 아직 담당/범위 승인이 없다. Order 담당이 이를 암묵적으로 맡거나 삭제할 수 없다.

## 현재 기준과 자료 상태

2026-11-13 브랜치 `refactor/#331-order-module`, HEAD `cc91c09b753a12a352bda65481210e264d3e88b0`. upstream 없는 브랜치에서 fetch·조상 관계 확인 후 `git pull --ff-only origin dev`를 실행했다. `e5a1b66b..cc91c09b` 사이 서비스·계약 변경은 없다. 기존 설계 파일 34개의 SHA-256이 동기화 전후 동일했다.

새 하네스에 맞춰 실행 계획·ADR·워크로그와 HTML의 같은 이름 MD를 연결한다. 모듈 목표 HTML은 이전에 만든 FigJam 스타일 초안으로 유지한다. 저장소 `docs/design-docs/services/commercecore/`의 MD·생성 HTML은 현재 구현 자료이므로 구현 시 함께 갱신한다.

FigJam 원본은 [Order spec 399:2153](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=399-2153), 상세설계는 [473:1285](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=473-1285)다. 상세설계에 기술안/미결정이 표시돼 있다. HTML은 실제 작성·화면 확인을 마쳤지만 업무 정책 승인을 뜻하지 않는다.

### 2026-11-13 후속 인계 반영

코디네이터 메시지 `msg_14d5fd3688ad`와 `/tmp/commercecore-module-handoff-20261113/통합-경계-1차-결정.md`, `신규-판매준비-확정.md`를 확인했다. 문서 원문의 역할은 다음과 같이 구분한다.

- **사용자 확정**: 신규 Product `sale_enabled=false`/SKU `READY`로 등록하고 판매자가 시작한다. 기존 데이터 상태는 유지하고 판매 후 READY로 수동 복귀하는 정책은 추가하지 않는다. Order 신규 생성은 판매 준비 상태에서 선점하지 않아야 한다.
- **코디네이터 기술 조정**: Product·Cart 항목·SKU ID는 BIGINT/Java Long, Sales ID를 기본 SKU ID에 보존하는 이행 우선. 충돌/매핑 불가는 재조정하며 Order ID/번호 전체 이행과 구분한다.
- Order는 공유 주문 서비스와 Core RefundIntake/Query/Outcome 조정자, `commercecore-service.yaml` **최종 편집자**다. 각 담당이 자기 API·DTO 변경 조각과 실제 의미를 제공한다. 업무 정책을 대신 결정하는 권한은 아니다.
- Cart는 저장·CRUD·선택 잠금·조건부 성공 정리 포트 담당이다. #340 보류 중 현행 Order의 Cart ID/version과 환불 참조를 유지한다. 목표 보존 위치·호환과 전체 잠금 순서는 미결정이다.
- SKU는 sales-info 이벤트·다중 SKU Catalog 판매 사본의 최소 호환, Product는 제어/삭제 이벤트·Catalog Product 사본의 해당 변경을 맡는다. `product_sales(product_id PK)`에 여러 SKU를 덮어쓰지 않는다. 대표가·최저가·합산재고와 외부 단일값 호환 정책은 자동 추가하지 않는다.
- FigJam은 코디네이터가 모듈별 **API·ERD·상태도** 중심으로 편집한다. 기존 Order 명세/흐름의 중복 편집을 피하고 차이와 근거만 인계한다. 새 migration 번호·PR base/순서, 옵션 JSON/Catalog 상세, U1~U3 정책은 여전히 미결정이다.

이 인계로 종결한 이전 감독 Task의 `failed`를 성공으로 변경하지 않는다. 구현·모델 변경 지시도 아니다.

## 현재 구현 체크포인트

- Core V104/Payment V4 배정. 기존 PK·물리명·Cart 출처·활성 주문 TTL을 보존하는 확장안 채택.
- Cart 첫 검증 커밋 b3091eae를 ff-only로 통합. 최종 SKU DTO는 후속 Cart 검증 커밋을 기다린다. 선행 순서는 Product→SKU→Cart→Order, PR base도 같은 순서이며 정확한 SHA의 merge만 사용한다.
- 신규 `/v1/order-sheets`는 UUID 주문번호, 같은 DB 트랜잭션 스냅샷/선점, DB 시각 600초, 정책 버전 1을 제공한다. 과거 API/구매는 정책 버전 0 호환 경로다. Core 코드는 작성 중이며 아직 선행 포트 연결·서비스 검증 전이다.
- Payment 종료/보상 최소 계약은 코디네이터 피드백을 반영해 구현 중이다. 실제 PostgreSQL / Mock gateway HTTP 회귀 26건 실패·오류·스킵 0을 확인했다. 신규 guard/플래그 범위·Core 종료 결과 적용까지 전체 연계 완료 증거는 아니다.
- U2 사용자 확정: 주문당 한 장/선택 항목 하나, 누적 전량 환불 성공 시 복원, 기존 유효기간 유지. U3: 결제 대기 중 즉시 취소 제한 유지. U1도 단가 우선/남은 항목 실결제액 상한으로 확정됐다. 순차 4,000/4,000/2,000, 일괄 8,000/2,000 및 0원 잔여 수량을 검증한다.
- HTML의 SHA/ID/MD 링크와 전체 보기·확대/탐색을 보완했다. ResizeObserver 폭 변화 가드 후 콘솔·1280/390px 검증을 갱신한다.

## 단계

- [x] 실제 코드·계약·FigJam 조사 및 사용자 확정 정책/권장안 분리
- [x] 상세설계·ERD·상태 초안과 원본 보존·화면 검증
- [x] 최신 dev 동기화와 기존 파일 보존 확인
- [x] 최신 지침의 실행 계획·ADR·워크로그·인계 보고 구성
- [ ] 아래 사용자 정책 결정과 네 모듈 연결안을 코디네이터가 통합 검토
- [ ] 공유 파일 소유자·DDL 번호·API 호환/적용 순서 결정 및 구현 착수 메시지
- [ ] Order/OrderItem·생성/기한/멱등성·SKU/Cart 연계 구현
- [ ] 부분 환불·쿠폰·종료 통지/늦은 성공 보상 구현 및 소비자 정합성 검증
- [ ] 현행 ERD/상태/API HTML 갱신, 로컬·외부 검사와 한계 기록
- [ ] 허가된 범위의 커밋·푸시·Order #331 PR 한 개 생성. 병합·배포는 범위 아님

## 구현 차단 결정

확정 정책의 재승인을 요청하지 않는다. 업무 정책은 사용자, 기술 연결·파일 분담은 코디네이터와 담당자가 정한다.

| ID / 결정 주체 | 남은 결정 | 권장안·실제 영향 | 기존 확정과 구분 |
|---|---|---|---|
| U1 / 해소 | 사용자 확정: 할인 전 단가×이번 수량을 남은 항목 실결제액으로 제한 | `orderItemId + quantity`, 순차 4,000/4,000/2,000; 일괄 8,000/2,000. 비례배분 제안 폐기 | 금액/수량 잠금·성공 적용·0원·실패 재시도 검증 진행 중 |
| U2 / 해소 | 사용자 확정: 주문당 한 장/선택 항목 하나, 누적 전량 복원, 원래 기간 유지 | 할인은 선택 항목에만 적용하고 기간 연장/재발급 없음 | 구현/검증 진행 중. U1 환불액/일부 수량과 별개 |
| U3 / 해소 | 결제 대기 중 즉시 취소 제한 유지 — 사용자 확정 | 요청 전 즉시 취소, 요청 후 원래 10분 만료/확정 후 환불 | 신규 기한 예외나 임의 연장 없음 |
| T1 / 통합 검토 | 옵션 스냅샷·모듈 호출/잠금 순서·Cart 출처/환불 호환 | 아래 DTO 의미와 한 DB 트랜잭션 계약을 맞춤 | ID는 BIGINT/Long으로 1차 조정됨. 별도 예약 테이블 없음·available/reserved·신규 판매 준비는 확정 |
| T2 / 통합 검토 | 종료 통지 수신자·형식, 순서 역전, 늦은 성공 보상 소유 | Core의 종료/보상 의도를 영속화하고 기존 Payment 요청 ID로 중복 차단. 미확정 결과는 조회/재시도 | 사용자 요구인 실패 주문 통지를 누락하거나 현행 SUCCESS/FAILED 이벤트가 지원한다고 가정하지 않음 |
| T3 / 통합 검토 | 물리 이행·기존 호출자 호환·활성 주문 처리·공유 파일/번호 | 기존 PK/물리명 유지 확장안을 우선. 활성 주문은 기존 경로 유지 또는 신규 생성 중지 후 종결 대기 비교 | 데이터 삭제/재가격 산정/기한 연장으로 이행을 단순화하지 않음 |

재진입 키 필수·동일 키/내용 재사용·한 회원 활성 주문 하나 유지·주문당 미확정 환불 한 건·쿠폰 로컬 미리보기/최종 서버 검증은 **기술 권장안**이다. 이 중 사용자에게 보이는 제한이 달라지는 부분은 통합 검토에서 실제 기존 동작과 대조하고 필요할 때만 추가 확인한다. 답변이 없다는 이유로 채택 상태로 바꾸지 않는다.

## 코드·테이블 소유 제안

아래 Java 경로는 `services/commercecore-service/src/main/java/com/shoppinglive/commercecore/` 아래다. 현재 패키지 위치와 최종 책임을 구분한다.

| 영역 | 실제 경로·테이블 | 구현 담당 제안과 제한 |
|---|---|---|
| 주문 aggregate·입력/조회 | `order/entity/Order.java`, `PaymentGroup.java`, `OrderAmounts.java`, `OrderCheckout.java`, `OrderStatus.java`; `order/controller/Order*`, `PaymentGroupController.java`; `order/dto/`, 주문/묶음 repository | Order. 최종 Java 이름/기존 API 어댑터는 이행안 선택 후 정함 |
| 공유 업무 서비스 | `order/service/OrderCreationService.java`, `OrderPaymentOutcomeService.java`, `PaymentGroupLifecycleService.java`, `PaymentGroupCheckoutService.java` | Order 단일 편집 창구. Cart/SKU/Product는 자신의 메서드 의미와 DTO를 먼저 제공 |
| 수명·가드·스케줄러 | `order/service/PurchaseGuard.java`, `PaymentGroupExpirationScheduler.java`, `OrderService.java` | Order. 15분/CONFIRMING 만료 제외를 목표 정책으로 복사하지 않음 |
| 부분 환불 업무 | `payment/service/RefundIntakeService.java`, `RefundOutcomeService.java`, 관련 조회/DTO; `refund_request`, `refund_target_order` | Order. 접수/항목별 수량·금액·성공 적용에 필요한 범위. Payment 실행 엔진 전체 변경 아님 |
| 전달/결과 경계 | `order/entity/PaymentDeliveryRequest.java`, `payment/service/PaymentDeliveryStore.java`, `PaymentDispatcher.java`, `PaymentResultHandler.java`, 관련 소비자 | Order가 Core 연계 편집을 맡는 안. 독립 Payment 수신/보상 기능 배정은 T2 선행 |
| 쿠폰 연결 | `coupon/`의 미리보기·예약·사용/복원 중 주문에 필요한 부분 | Order에 한정 배정 제안. 쿠폰 발급·관리 모듈 전체 재설계는 제외 |
| Cart | `order/`에 있는 CartController/CartService/CartItem/CartItemRepository/CartItemNotFoundException과 후속 Cart 패키지 | Cart #332. 이동/삭제 시 Order import·호출부는 Order 조정 |
| SKU·레거시 Sales | SKU 신규 패키지/테이블, `sales/` 이행 및 가격/수량 SQL | SKU #334. Order는 SKU DDL/엔티티를 작성하지 않음 |
| Product | `product/`, `products`, 이미지·판매허용·삭제·원본 스냅샷 제공 | Product #333 |
| 테스트 | `src/test/java`와 `src/integrationTest/java`의 대응 order/payment 경로 | 해당 업무 담당. 공통 fixture 파일은 편집 창구를 배정 |

Order 소유 물리 변경 후보는 `payment_group`, `orders`, `refund_request`, `refund_target_order`, `member_purchase_guard`, Order 연계에 필요한 `payment_delivery_request`다. 목표 필드·타입·고유/체크/인덱스와 이행은 [ERD 설명](../../../design-docs/commercecore/order/ERD.md)에 있다. 기존 `event_outbox` 구조/common-module은 재사용하고 별도 범용 프레임워크 개편을 추가하지 않는다.

## 다른 모듈에 필요한 인터페이스 의미

아래 이름은 설명용이며 코드/DTO 계약 구현이나 합의가 아니다. 다른 모듈 JPA 엔티티를 API DTO로 노출하는 대신 필요한 불변 값을 전달하는 안이다.

| 협력 대상 | 입력·출력 의미 | 필수 경계 |
|---|---|---|
| SKU 구매 스냅샷/선점 | SKU ID·양의 수량 목록 → SKU/Product 식별, 현재 KRW 단가, 식별/표시용 정보 | 가격 조회와 선점 일관성, 모든 항목 전체 성공/롤백, Order 트랜잭션 참여. SQL 순서·격리는 SKU가 제공 |
| SKU 예약 확정/해제/환불 복구 | SKU ID·수량 목록, Order가 적용 가능성을 판정한 명령 | `confirm: R-q`, `release: A+q,R-q`, `restock: A+q`. 모듈이 임의 독립 커밋/별도 예약 원장을 만들지 않음 |
| Product 구매 가능성 | Product ID 목록 → 미삭제/전체 판매허용, 이름·판매자·필요한 표시 정보 | Product 잠금 후 SKU 순서를 맞춤. 기존 주문의 해제/환불 복구에 신규 판매 허용을 요구하지 않는 안 |
| 옵션 표시 공급 | SKU 선택과 일치하는 당시 옵션 이름/값 JSON | Catalog 관리 방향 보존. 저장·검증·버전·공급 경로 미결정. 가격/재고 원본과 분리 |
| Cart 선택 | 인증 회원, cartItemId/version 목록 → SKU ID/수량/원본 출처 | 조회에서 재고 선점하지 않음. 같은 생성 키 재시도는 먼저 기존 Order를 반환해 Cart 변경 후에도 재사용 |
| Cart 성공 정리 | 저장한 sourceCartItemId/sourceCartVersion | 결제 성공 때 일치하는 원본만 삭제하는 안. 이후 편집·삭제 후 재등록 항목 보존 |
| Payment 연계 | 요청 ID, 주문/기존 결제 참조, 금액/통화, 확정 결과 ID/상태 | Core 업무 상태와 실행 결과 분리. 종료→결제 생성 순서 역전·중복·보상 결과 재처리 지원 필요 |

생성/결과/환불에서 공통 잠금 순서는 회원 가드 → Order → 환불/전달 → Cart → 쿠폰 → 모든 Product ID 오름차순 → 모든 SKU ID 오름차순을 **검토안**으로 둔다. 여러 담당의 기존 경로와 역순 잠금 여부를 함께 확인해야 한다. 잠금 후 서버 시각으로 만료를 판단하고 외부 HTTP는 커밋 후 호출한다.

## 공유 계약·문서·이행 순서 제안

| 공유 경로 | 분담 제안 | 선행/소비자 |
|---|---|---|
| `contracts/api/commercecore-service.yaml` | **Order 최종 편집자로 1차 배정됨.** Order 주문/환불, Cart `/v1/cart*`, Product 상품, SKU 판매/SKU의 담당이 자기 변경 조각·DTO 의미를 제공하고 Order가 통합 편집 | 새 정책 승인 권한은 아님. 기존 productId/Cart ID/PaymentGroup 호출자 이행과 네 PR 반영 순서 조정 필수 |
| `contracts/api/payment-service.yaml` | T2 결정 후 필요한 최소 종료/보상 계약의 편집자 배정 | 독립 Payment 생산자/소비자 구현과 같이 검증. YAML만 추가하지 않음 |
| `contracts/events/commerce-payment-group.yaml` | Order가 주문 성공 의미/구버전 호환 검토 | Notification 등 실제 소비자와 이벤트명/버전/식별 의미 대조 |
| `contracts/events/payment-execution-results.yaml`, `payment-refund-results.yaml` | Order는 결과 소비 영향 조사. 외부 payload 변경 시 독립 Payment 편집 범위 먼저 배정 | UNKNOWN 이벤트를 추가한다고 가정하지 않음 |
| `commerce-product-info.yaml`, `commerce-sales-info.yaml` | Product 제어/삭제 이벤트·Catalog Product 최소 변경은 Product, sales-info·다중 SKU Catalog 판매 사본 최소 호환은 SKU로 1차 배정됨 | 외부 단일값 호환안은 소비자 조사 후 별도 확정. 대표가/최저가/합산재고 정책을 추가하지 않음 |
| `src/main/resources/db/migration/V*__*.sql` | 코디네이터가 충돌 없는 새 버전/순서를 배정. 각 담당이 자기 테이블의 새 파일만 작성 | 현재 V100이 있으므로 다음 번호를 V22로 임의 선택하지 않음. 적용된 과거 SQL 수정 금지 |
| `docs/design-docs/services/commercecore/erd.md` | Product→SKU→Cart→Order 순으로 자기 변경만 반영하는 안 | 파일 전체 소유권으로 다른 모듈 내용을 덮어쓰지 않음; HTML 재생성은 순차 수행 |
| `services/commercecore/states/order.md`, `payment.md`, `refund.md`와 상위 주문·환불 흐름 | Order가 구현 영향 반영. 경로는 `docs/design-docs/` 아래 | 원본 MD 수정 후 HTML 생성. API HTML도 YAML에서 재생성 |

통합 순서 제안은 **Product 식별/판매허용·옵션 공급 정리 → SKU 생성/가격·재고/레거시 Sales 어댑터 → Cart SKU 선택/출처 → Order 구매/환불 적용**이다. Product 생성이 SKU 초기화를 호출하는 부분은 SKU 후속 연결 또는 호환 seam으로 순환 의존을 끊는다. 네 PR은 유지하되 선행 PR 의존을 표시하고 병합 가능한 상태를 각 PR에서 검증한다. 별도 다섯 번째 통합 PR이 필요하다고 임의 변경하지 않는다.

새 구매 전환 전에는 기존 활성 구매를 유지할지 잠시 생성 중지 후 종결 대기할지 결정해야 한다. 예전 API ID를 새 의미로 무단 재해석하거나 현재 SKU 가격으로 과거 주문을 backfill하지 않는다. 이행 이후에도 과거 결제/환불 재처리의 상관관계가 유지돼야 한다.

## 검증 계획

Python은 이번에 준비한 `build/order-design-venv/bin/python` 또는 저장소 고정 의존성이 있는 `.venv-api/bin/python`, Java는 21이다. 아래 구현 검사는 아직 실행하지 않았다.

| 구분 | 명령·환경 | 확인 범위·한계 |
|---|---|---|
| 선택/문서 | `PYTHON=build/order-design-venv/bin/python scripts/check.sh --base origin/dev --dry-run`; `scripts/check-docs.sh`; `scripts/render-api-docs.sh --check`; `python scripts/render_design_docs.py --check` | 문서 링크·생성 최신성. 설계 동작이나 실제 DB 보장 아님 |
| 단위·기본 통합 | `./gradlew :services:commercecore-service:test :services:commercecore-service:integrationTest :services:commercecore-service:spotlessCheck` | 한국어 DisplayName, 수량/금액 경계·멱등성·권한. 테스트 설정에 따라 Mock/H2/EmbeddedKafka 구분; 조건부 Kafka 스킵 별도 보고 |
| API/이벤트 정적 | `python .github/scripts/api/check_structure.py`; `python .github/scripts/api/check_events.py` | 매핑/스키마/예시. 실제 DTO/업무 의미는 별도 대조 |
| 직렬화 표본 | Core `test --tests '*EventContractTest' --rerun-tasks` 후 `check_events.py --samples` | 현재 코드의 표본 생성 성공 전제. 브로커 전달 증거 아님 |
| 실제 PostgreSQL | 격리 `COMMERCECORE_TEST_POSTGRES_URL/USER/PASSWORD`로 `:services:commercecore-service:postgresTest` | 선점 전체 롤백, 성공/만료 경합, 환불/쿠폰 중복 결과, 다중 인스턴스 키·잠금 순서. Payment fixture가 필요하면 별도 PAYMENT_TEST_POSTGRES_* DB |
| 이행 PostgreSQL | `:services:commercecore-service:migrationTest` | `postgres & migration` 태그 대상, 과거 번호/금액/쿠폰/환불 참조 보존·활성 주문 표본 |
| Kafka | 격리 `COMMERCECORE_TEST_KAFKA_SERVERS`, 실제 관련 통합 테스트를 `--rerun-tasks`로 실행 | 현행 조건부 SalesInfoEventKafkaTest/CouponSyncKafkaTest는 별도 실행. 새 종료/결과 경로는 구현한 실제 소비자→DB 반영까지 확인 |
| Redis | Order에 새로운 Redis 경로 없음 | 근거 없이 redisTest를 실행하거나 통과로 기록하지 않음. 변경 의존이 생기면 실제 대상·격리 환경부터 확인 |
| PG/AWS·CI | 실제 연동 범위와 원격 PR 검사 | 현재 Payment Mock 실행과 구분. 이 단계에서는 전부 미실행, 서비스/배포 증거 없음 |

필수 시나리오는 상세설계 §13을 따른다. 실행 건수 0·스킵·미실행·캐시 재사용은 실제 성공과 구분한다. 최종 diff가 바뀌면 해당 검증만 갱신한다.

후속 판매 준비 정책의 검증 계획: 신규 Product false/SKU READY에서는 주문 생성·선점 거절, 판매 시작 후 양쪽 조건과 재고가 충족되면 생성 가능, 기존 데이터 상태는 이행 전후 동일해야 한다. 이미 선점된 주문의 해제/성공 환불 복구에는 새 판매를 허용하는 검사를 잘못 재사용하지 않는지도 확인한다. 이 시나리오는 아직 실행하지 않았다.

## 환불 정책 근거

[공식 정책 조사](refund-policy-research.md)를 보존했다. 외부 서비스가 우리 계산식과 같다고 확인된 것은 아니며 사용자 확정 구현을 바꾸는 근거로 사용하지 않는다.

## 인계

설계 문서와 HTML 초안은 존재하며 새 지침에 맞는 추적 대상 문서로 보존한다. 이 계획은 미결정 정책과 구현 작업이 남았으므로 `active/`에 유지한다. 이번 감독 작업 보고는 로컬 `build/design-handoff/Order-설계-완료-보고.md`이며, 공유 저장소에 없는 build 자료를 팀 문서의 필수 링크로 삼지 않는다.

Order FigJam의 마지막 편집은 상세설계 473:1285, 기존 spec 399:2153, 스티키 117:702·130:2580·130:2738·129:884·117:797·400:2305다. 2026-11-13 재조회에서 상세설계 315줄, 원본 64줄(후행 개행 포함), 두 문서 모두 `hasMore=false`였다. 원본 캔버스 미리보기 지연과 새 로컬 기준 SHA/명령 반영 필요는 코디네이터에게 전달한다.
