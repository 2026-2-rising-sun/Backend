# #332 Cart 모듈 구현 계획

상태: 진행 중 · 통합 검토용 초안 준비 · 연계 설계 미완료 · 구현 미착수

관련 자료: [설계와 대안](../../../design-docs/commercecore/cart/설계.md) · [Cart #332](https://github.com/2026-2-rising-sun/Backend/issues/332) · [정리 정책 #340](https://github.com/2026-2-rising-sun/Backend/issues/340) · [FigJam](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=465-1282)

결정·검증 기록: [ADR 0332](../../../adr/0332-cart-sku-boundary.md) · [워크로그](../../../worklog/2026-11-13.md#332-cart-설계-통합-검토-준비)

작성일: 2026-11-13. 현재 기준: `refactor/#332-cart-module`, `cc91c09b753a12a352bda65481210e264d3e88b0`. 최초 코드 조사 기준 e5a1b66b에서 fast-forward 동기화했고 기존 Cart 문서 세 파일의 내용 보존을 해시로 확인했다. Cart 업무 코드는 이 사이에 바뀌지 않았다.

현재 Task는 설계 통합 검토 준비다. 코디네이터의 명시적인 구현 착수 메시지 전 코드·DB·API/이벤트 계약·커밋·푸시·PR을 시작하지 않는다. 구현 전환 때 코디네이터가 이 동일 세션에 `gpt-6.1-sol / medium`을 적용·재조회 확인한다. 스스로 모델이나 전역 기본 설정을 바꾸지 않는다.

## 목표와 경계

Cart의 항목·수량·소유권 책임을 분리하고, Product별로 묶은 SKU 항목을 조회·조작하도록 전환한다. Cart 담기·조회는 예약하지 않는다. 주문서 화면 생성 시 Order가 서버 현재 SKU 가격을 저장하고, 이후 주문서·결제는 그 스냅샷을 사용한다. 현재 가격과 재고 원본은 SKU 모듈이다.

**결제 성공 후 장바구니 정리는 고려해야 할 미결정 항목으로 #340에 분리했다.** 성공 후 정리 시점은 유지한다. 연결 필드·버전 저장·편집 제한·삭제 수량 계산 등 새 정책은 이 계획으로 승인하지 않는다. 정책 합의 전 현행 동작을 임의로 없애지 않는다. 사용자 직접 삭제 CRUD는 Cart 기본 범위에 포함한다.

신규 쿠폰 설계, 게스트/복수 장바구니, Cart TTL, 별도 재고 예약 테이블, 독립 Payment 개편, Catalog 구조 변경을 자동으로 추가하지 않는다. 기존 쿠폰·환불 연계는 호환성 검토 대상이다.

## 반영한 후속 결정

2026-11-13 사용자 확정으로 신규 Product는 `sale_enabled=false`, 신규 SKU는 `READY`에서 시작하고 판매자가 판매 시작을 선택한다. **U1의 생성 직후 판매 여부는 해소됐으므로 다시 질문하지 않는다.** 기존 상품 상태를 바꾸거나 READY 수동 복귀 전이를 추가하지 않는다. Cart에서 생성만으로 구매 가능하다고 표시하지 않으며, READY 상품 담기 허용·재고 0 판매 시작 응답은 별도 계약 검토 범위다.

코디네이터의 1차 기술 결정은 Product/Cart/SKU BIGINT·Long 유지, 기존 Sales ID를 기본 SKU ID로 보존 우선, Core API YAML 최종 편집자 Order, #340 보류 중 Cart ID/version 정리·환불 참조 보존이다. 사용자 업무 정책과 구분해 반영한다. 근거 원문은 `build/design-handoff/신규-판매준비-확정.md`, `통합-경계-1차-결정.md`에 보존했다. 기존 설계 failed 판정은 유지하며 새 Task/구현 착수·모델 변경으로 간주하지 않는다.

FigJam 산출물은 사용자 최신 지시에 따라 모듈별 API 목록·ERD·상태도를 중심으로 코디네이터가 편집한다. Cart는 확정 Method/Path·요청/응답 핵심·필드/관계·작업 조건을 `build/design-handoff/Cart-FigJam-API-ERD-인계.md`로 전달한다. 현재 API와 미구현 목표를 구분하고, 별도 Cart 상태 enum이나 상태 테이블은 추가하지 않는다. 정책/선택 이유는 기존 로컬 ADR과 설계에 보존한다.

## 구현 전에 맞출 연계 항목

| 항목 | 필요한 결정 | 담당과 진행 조건 |
|---|---|---|
| 항목·SKU 식별 | BIGINT·Long은 고정. 회원+SKU 유일성, 기존 productId 매핑 검증, productId 중복 저장 여부 | 기존 Sales ID 보존 우선. 충돌·다중 후보·매핑 불가만 Product #333·SKU #334와 조정 |
| 조회 정보 | Product 이름·이미지·옵션, SKU 현재가·판매 가능 정보, 일괄 조회와 항목별 실패 표현 | SKU의 `skuId/productId/현재가/available/reserved/개별 상태` 포트안 검토. 외부 DTO는 별도 합의 |
| 담기·수량 규칙 | SKU 전환 후 중복 담기, 판매 중지/미존재 SKU, 수량 범위의 응답 | 현행 중복 409·양수 검증을 출발점으로 삼고 업무 정책 변경은 별도 결정 |
| 주문서·재진입 | 선택 항목 전달, 동일 주문 재조회, 요청 재전송, 수량 변경 시 새 주문 처리 | Order #331. 현재 checkout 견적 API와 orders 생성 API의 변경 책임도 함께 정함 |
| 자동 정리 | 기존 Cart ID/version 정리·환불 참조 보존 원칙은 고정. Order 목표 모델의 위치·호환 세부는 대기 | #340 신규 정책은 보류. 기존 참조를 임의 제거하지 않음 |
| 공유 파일 | Core API YAML 최종 편집자 Order는 고정. API 필드·경로, 마이그레이션 번호·적용 순서는 대기 | Cart는 변경 조각과 실제 구현 의미를 제공. 단독 편집 책임을 새 업무 정책 결정 권한으로 확대하지 않음 |

SKU 담당 계획에는 다중 SKU 전환 시 Catalog 소비자 영향도 선행 조율로 남아 있다. Cart는 이 일을 독자적으로 구현하거나 완료로 간주하지 않는다. Order의 만료·환불 상태 정책은 해당 담당이 소유하며 Cart에 중복 상태·타이머를 만들지 않는다.

## 변경 대상과 책임안

현재 Java 경로의 공통 접두사는 `services/commercecore-service/src/main/java/com/shoppinglive/commercecore/`다. 아래 경로와 이름은 구현안이며 최종 파일 구조는 동기화 후 확인한다.

| 대상 | 계획 | 편집 책임 |
|---|---|---|
| `order/entity/CartItem.java` | `cart/entity`로 이동하고 합의한 SKU 참조로 전환 | Cart |
| `order/repository/CartItemRepository.java` | `cart/repository`로 이동. 회원 소유권과 회원+SKU 조회·유일성 반영 | Cart |
| `order/service/CartService.java` | `cart/service`로 이동. CRUD 및 표시 정보 조합 책임 분리 | Cart |
| `order/controller/CartController.java`, `CartItemNotFoundException` | Cart CRUD 책임 이동. 포함된 주문 생성 경로는 Order와 분리 범위 합의 | Cart·Order 조율 |
| `CartOrderCommand`, `PaymentGroupController`, `OrderCreationService`, `PaymentGroupCheckoutService` | Cart 선택 정보 전달과 Order 생성/재진입 연결. Order 상태·금액 로직은 Order 소유 | Order 조정, Cart가 필요한 내부 호출 경계 제시 |
| `OrderPaymentOutcomeService`, 선택 환불 참조 | 현재 정리 동작 보존 여부 점검. 정책·필드 변경은 #340 결정에 따름 | Order·Cart 조율, 동시 편집 금지 |
| Product/SKU 조회 포트 | 필요한 SKU/상품 묶음을 일괄 읽기. 소유 엔티티 직접 수정 없이 조회 결과 조합 | Product·SKU 제공, Cart 사용 |
| `contracts/api/commercecore-service.yaml`, Flyway | 합의된 Cart 변경만 목록화해 Order가 YAML 최종 통합. 기존 migration 덮어쓰기 금지 | migration 번호·적용 순서는 별도 배정 대기 |

Cart 테이블 외에 별도 장바구니 헤더·선택 상태·가격 스냅샷·주문 연결 테이블을 이 단계에서 추가하지 않는다. 필요성이 확인되면 설계 결정과 비용을 먼저 남긴다.

### 내부 호출·DTO 권장안

아래 이름과 필드는 코드/계약 구현이 아닌 통합 검토용 제안이다. Product/Cart/SKU ID는 1차 결정의 Long/BIGINT를 사용하며 나머지 필드·오류 표현은 소유자와 맞춘다.

| 호출자 → 제공자 | 제안하는 입력·출력 | 책임과 제약 |
|---|---|---|
| Cart → SKU | SKU ID 목록 → SKU ID·Product ID·현재 단가·판매 가능 재고·개별 상태·항목별 조회 결과 | SKU #334의 일괄 조회 포트를 사용. reserved는 내부 값이며 외부 Cart 응답에 그대로 공개하지 않는 안을 권장 |
| Cart → Product | Product ID 목록 → 상품명·대표 이미지·전체 판매 허용 여부·담긴 SKU 옵션 표시 정보 | Product #333 소유. 옵션 JSON 저장 위치·외부 응답은 해당 담당 합의와 맞춤 |
| Order 생성 조정자 → Cart | 회원 ID·선택 Cart ID 목록 → 소유 검증된 Cart ID·SKU ID·수량·현행 version | Cart가 제공. 필요하면 호출자 트랜잭션에서 ID 정렬 잠금. Order가 Cart 저장소를 직접 변경하는 결합을 줄임 |
| Order 성공 처리 → Cart | 회원 ID·현행 Cart ID/expectedVersion 목록 → 조건부 삭제 결과 | #340 보류 기간의 동작 보존안. Order의 저장 참조와 새 인터페이스 확정은 별개이며 Order 담당과 조율 |
| 화면 → Order | 생성 요청의 선택 항목·합의한 요청 식별값 → 주문 식별자와 저장된 품목/금액 | Order #331이 생성·재조회·재전송을 담당. Cart는 주문 UUID 생성·멱등키·새 TTL을 정하지 않음 |

Product/SKU의 조회 계약을 먼저 맞춘 뒤 Cart 조회 DTO와 신규 요청의 `skuId`를 정의한다. 기존 상품 ID 요청은 명시적 단일 매핑이 있을 때만 호환시키는 안을 권장한다. 0개/다중 후보는 자동 선택하지 않는다. 기존 체크아웃 버전 필드를 제거할지는 #340 및 Order 계약과 함께 판단한다.

공통 YAML 최종 편집자는 1차 결정에 따라 Order다. Cart/Product/SKU는 경로·스키마 변경 조각과 실제 의미를 제공한다. Cart는 `/v1/cart/items`와 항목 PATCH/DELETE 및 조회 DTO를, Order는 현재 `/v1/cart/checkout`, `/v1/cart/orders`, `/{itemId}/orders`와 환불 계약을 조정한다. SKU #334가 `contracts/events/commerce-sales-info.yaml`과 다중 SKU Catalog 판매 사본의 최소 호환 변경을, Product #333이 Product 제어/삭제 이벤트와 해당 Catalog 사본 변경을 맡는다. 외부 단일값 호환 표현은 추가 검토하며 Cart가 Catalog를 수정하거나 전용 이벤트를 만들지 않는다.

## 구현 순서와 완료 기준

1. **기준과 의존성을 맞춘다.** cc91c09b 동기화와 새 AGENTS 확인은 완료했다. 구현 착수 전 원격 변화가 있으면 사용자 변경을 보존해 다시 비교한다. Order/Product/SKU ID·포트·이행·공유 파일 책임을 확인한다. 원본 dev나 다른 담당 워크트리는 수정하지 않는다.
2. **Cart 책임을 분리한다.** Cart 엔티티·저장소·CRUD·예외와 테스트를 `cart`에 배치한다. Order가 필요로 하는 선택 항목 조회·소유권 검증 경계를 최소한으로 둔다. 동작 변경을 섞지 않고 기존 호출과 결제 성공 후 정리·환불 참조가 유지되는지 확인한다.
3. **SKU 저장과 데이터 이행을 적용한다.** 합의된 SKU ID와 회원+SKU 유일성, 양수 수량 검증을 반영한다. 기존 행의 상품→SKU 매핑을 검증하고, 매핑 불가·다중 후보는 임의 선택하지 않는다. SKU 기반 schema/조회가 준비된 뒤 Cart 이행을 적용한다. 구 필드/API 제거는 호환성 합의 뒤 수행한다.
4. **상품별 조회와 현재가를 구성한다.** 담긴 SKU만 조회해 Product별로 묶는다. 이름·이미지·옵션·현재가·구매 가능 표시를 합의한 응답으로 조합한다. 예약 없이 일괄 조회하고, 미존재·판매 중지·품절·조회 실패의 응답을 검증한다. 전체 예약 수량을 외부에 노출할지는 별도 결정한다.
5. **주문서 흐름을 연결한다.** 서버가 선택 항목의 소유권·수량을 확인하고 Order 생성에 전달한다. 주문 생성과 가격 스냅샷·SKU 예약은 Order 담당 흐름에 참여한다. 재진입은 합의한 동일 주문 식별을 사용하여 스냅샷과 예약이 중복 생성되지 않도록 검증한다. Cart 조회 가격으로 기존 Order 금액을 갱신하지 않는다.
6. **회귀와 계약을 확인한다.** SKU별 CRUD·상품별 묶음·권한·현재가/스냅샷 구분·예약 없음·이행을 검증한다. #340 정책을 이번 변경에 포함하지 않는 경우 현행 정리·환불 호환성을 회귀 기준으로 유지하고 미결정으로 남긴다. 기존 경로가 깨지는 변경은 #340 결정 또는 호환 방안 없이 완료 처리하지 않는다.
7. **설계와 결과를 실제 구현에 맞춘다.** Cart는 로컬 Markdown·공통 ERD의 Cart 변경분·검증 결과를 갱신하고, FigJam 변경 내용·코드/계약 근거·node-id를 코디네이터에게 전달한다. 구현 단계 FigJam 편집은 코디네이터 단독 담당이다. 별도 Cart 상태 전이가 없다면 상태관리도.html을 생략한다. 최종 Cart #332 PR 한 개에 의존 이슈를 연결하며 #340은 별도 PR 분담을 늘리는 근거로 쓰지 않는다. 병합·배포는 포함하지 않는다.

## 검증 계획

아래 표는 실행 예정 시나리오다. 현재 통과 결과가 아니다. 기존 테스트를 우선 재사용하고 변경된 업무 동작에 필요한 사례만 보강한다.

| 시나리오 | 기대 결과 | 검증 범위 |
|---|---|---|
| 한 상품의 서로 다른 SKU 담기 | 별도 행·수량으로 유지하고 한 상품 아래 표시. 다른 SKU의 수량·삭제에 영향 없음 | Cart 서비스·API |
| 다른 회원 항목 조회·변경·선택 주문 | 데이터와 금액 정보가 노출되지 않고 처리 거절 | Cart API·Order 연계 |
| 중복 담기·수량 0/음수·범위 초과·동시 요청 | 합의한 응답과 유일성 유지, 수량 손상 없음 | 단위 및 실제 PostgreSQL |
| Cart 담기·조회·수량 변경 | SKU available/reserved를 변경하지 않음 | Cart+SKU 통합 |
| 신규 Product/SKU 생성과 판매 시작 | sale_enabled=false·READY를 구매 가능으로 표시하지 않음. 판매 시작 뒤 Product/SKU·재고 조건으로 다시 판단. 기존 상품 상태는 보존하고 READY 수동 복귀를 추가하지 않음 | Product/SKU 조회·Order 생성 연계, 구현 후 실행 예정 |
| SKU 가격 변경 후 Cart 조회 | 새 현재가 표시. 이미 만든 주문의 저장 가격은 유지 | Cart 조회·Order 스냅샷 |
| 주문서 재전송·재진입 | 합의한 같은 주문으로 확인되고 재고 중복 예약 없음 | Order+SKU 연계 |
| A 2개·B 5개·C 8개, A/B 선택 후 편집 없음 | 결제 성공 후 A/B만 정리하고 C 유지. 생성·실패 시 자동 삭제하지 않음 | 현행 회귀 또는 #340 합의 정책 |
| 결제 중 수정·삭제 후 재추가·결과 중복·정리 실패 | 현행 유지 구간은 기존 보호 동작 유지. 새 기대 결과는 #340에서 확정 후 추가 | 실제 PostgreSQL 경합·결제 결과 경로 |
| 직접 구매·기존 주문의 선택 환불 | 무관한 Cart 손상 없음. Cart 참조 변경 시 과거 주문도 환불 대상 식별 가능 | Order·환불 계약 회귀 |
| 기존 데이터·구 API | 합의한 매핑·병행 정책 검증, 다중 SKU 임의 선택 없음 | migration·계약·소비자 대조 |

기존 참고 테스트는 `CartOrderIntegrationTest`, `PaymentGroupIntegrationTest`, `CartCouponPreviewApiTest`다. 가격·예약 없음과 변경된 Cart 보존 사례를 확인했지만 이번 문서 작업에서 테스트를 실행한 것은 아니다. 쿠폰 테스트는 기존 동작의 회귀 확인에 사용하며 새 쿠폰 정책은 추가하지 않는다.

현행 하네스 기준 실행 후보는 다음과 같다. API 검사 의존성이 설치된 Python 환경을 사용하며, 구현 시 최신 규칙으로 대상·환경을 다시 선택한다.

```bash
scripts/check.sh --dry-run
scripts/check.sh
scripts/check-docs.sh
scripts/render-api-docs.sh --check
python3 scripts/render_design_docs.py --check
./gradlew :services:commercecore-service:test :services:commercecore-service:integrationTest
./gradlew :services:commercecore-service:postgresTest
./gradlew :services:commercecore-service:migrationTest
```

PostgreSQL은 격리 DB와 `COMMERCECORE_TEST_POSTGRES_` 환경 설정 및 실제 연결을 확인한다. H2·Mock·EmbeddedKafka 결과를 외부 환경 검증으로 보고하지 않는다. API 변경 시 계약 규칙의 원본 수정·호환성·생성 결과 검사를 추가한다. 이벤트 변경이 없다면 Kafka/PG 검증을 Cart 구현의 형식적 필수 단계로 늘리지 않는다. 실행 건수·스킵·미실행을 구분해 남긴다.

## 이번 작업의 완료 상태

- [x] 사용자 확정 사항과 미결정 삭제 정책을 설계 문서에 구분했다.
- [x] 후속 검토 이슈 #340을 만들고 원본 Cart 흐름 옆 FigJam spec에 연결했다.
- [x] 구현 순서·담당 경계·선행 결정·수용 기준을 작성했다.
- [ ] 코드·DB·API/이벤트 계약 구현과 서비스 검증: 아직 시작하지 않았다.
- [ ] 목표 SKU/Cart ERD 반영·필요한 상태관리도·최종 PR: 구현 단계의 산출물이다. 현행 공통 ERD MD/HTML은 이미 존재하며 목표 모델 완료 증거가 아니다.

최신 dev 동기화 후 docs Markdown은 더 이상 ignore 대상이 아니다. 이 문서와 설계/ADR은 아직 Git 미추적 로컬 초안이고 스테이징·커밋·푸시는 하지 않았다. 최종 PR에 문서가 누락되지 않도록 확인한다.

이전 기록: 문서 내부 링크, 이슈 생성 결과, FigJam 본문·기존 여섯 노드 보존·배치를 확인했다. 동기화 전 `python3 harness/verify.py --dry-run`은 `tree_sitter_java` 의존성 누락으로 실패했다. 현재 검사 진입점은 `scripts/check.sh`이며 이번 결과는 위 워크로그에 기록한다. 서비스 테스트·DB·브로커·PG 검증은 아직 미실행이다.

## 통합 검토에 남긴 결정

정리 방식의 신규 업무 정책은 사용자 요청으로 #340에 보류했고 현행 정리·환불 참조 보존 원칙은 고정했다. Order 목표 모델의 참조 위치·재진입 계약, 옵션/상태 외부 응답, 매핑/구 API 이행 세부, migration 번호·PR 적용 순서·공유 ERD 편집 순서·전체 잠금 순서가 남았다. Product/Cart/SKU BIGINT·Long, Core API YAML 최종 편집자 Order, U1 신규 판매 준비는 해결된 항목이므로 재질문하지 않는다. 보완 Task와 통합 검토를 기다리며 설계 전체 완료나 구현 가능으로 판정하지 않는다.
