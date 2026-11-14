# ADR 0334: SKU 모듈과 테이블에서 현재 가격과 재고를 함께 관리한다

결정 상태: SKU 통합·금액·예약 멱등성·가격 확정 시점 채택 · 모듈 연계 협의 중
구현 상태: 기존 단일 판매 HTTP·Product 판매 조회를 SKU로 전환 · Order/Cart 최종 통합과 공개 다중 SKU 흐름은 별도
관련 작업: [#334](https://github.com/2026-2-rising-sun/Backend/issues/334) · [작업 기록](../worklog/2026-11-13.md#334-sku-모듈테이블-통합-방향과-figjam-반영)
실행 계획: [SKU 구현 계획](../exec-plans/active/334-sku-module/README.md)
시각 자료: [SKU 영역](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=118-2257) · [SKU 통합 spec](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=424-1279)

## 문제와 대안

상품은 이름·설명·이미지 같은 표현 정보와 옵션 조합별 판매 단위인 SKU를 포함한다. 가격과 재고를 상품 ID에 직접 연결하던 현행 구조에서 SKU 기준으로 옮기는 과정에, Price·Inventory를 각각 별도 테이블·모듈로 둘지 SKU로 묶을지 결정해야 했다.

| 대안 | 이점 | 비용 |
|---|---|---|
| SKU·Price·Inventory 별도 테이블·모듈 | 가격과 재고의 저장 책임을 나누고 추후 서로 다른 구조로 확장하기 쉽다. | 현재가와 재고의 초기화·조회·주문 검증을 여러 경계에서 조율해야 한다. 테이블 분리만으로 잠금이 독립되는 것은 아니다. |
| SKU 테이블·모듈로 통합 | SKU ID, 현재 판매가, 재고 데이터를 한 저장 모델에서 관리하고 초기화·변경 책임을 모을 수 있다. | 같은 SKU의 가격 변경과 재고 변경이 같은 행에서 경합한다. 필드별 갱신과 버전·트랜잭션 규칙을 함께 설계해야 한다. |

사용자는 후자를 요청했다. 현재 범위에는 가격 이력·예약 가격·창고별 재고 같은 별도 저장 모델이 필요한 정책이 합의되어 있지 않아 통합 방향을 채택한다. 성능 개선을 측정하거나 보장한 결정은 아니다.

## 결정과 경계

- Product는 이미지·이름·상품설명 등 상품 표현 정보를 관리한다. Product 한 개에 여러 SKU가 연결된다.
- SKU는 옵션 조합별 판매 단위이며 가격과 재고 데이터를 같은 테이블에서 관리한다. 독립 Price·Inventory 모듈 대신 SKU 모듈의 기능으로 구성한다.
- 사용자는 Order #331·Cart #332·Product #333·SKU #334의 4개 PR 구성을 확정했다. 기존 Price 담당이 SKU 현재 가격과 재고 전체의 단일 담당이 되며 Inventory #335는 #334로 통합한다. Product의 상품 표현 정보·Product–SKU 구성/옵션 담당과 협력한다.
- 가격과 재고의 변경 동작은 구분한다. 모듈 통합은 모든 기능을 하나의 거대한 서비스 메서드로 작성한다는 뜻이 아니다.
- SKU 재고는 사용자 답변에 따라 `available`(판매 가능)·`reserved`(주문에 예약된 수량) 두 값으로 확정한다. 별도 예약 테이블은 두지 않는다. Product 전체 판매허용 스위치와 SKU 개별 판매 상태를 분리하고 전체 중지·재개로 SKU 상태를 덮어쓰지 않는다. 옵션 JSON의 저장·연계와 상태의 세부 표현은 협의 중이다.
- 사용자는 신규 Product·SKU를 판매 준비 상태로 생성하고 판매자가 시작하도록 확정했다. 신규 Product는 sale_enabled=false, SKU는 READY로 생성한다. 기존 상품/SKU 상태는 보존하며 READY로 수동 복귀하는 전이는 추가하지 않는다. 품절 파생과 외부 응답의 세부는 기술 검토 중이다.
- Order는 주문 당시 상품·옵션·가격을 스냅샷으로 보존한다. 사용자가 정한 주문서 화면 생성 시점에 주문을 만들고 그 주문 금액을 표시한다. SKU의 현재가 변경으로 기존 주문 금액을 다시 계산하지 않는다.
- Catalog는 조회 사본을 유지한다. 내부 테이블 통합만으로 API·이벤트 키와 버전, Catalog 소비자 계약을 자동 변경하지 않는다.

## 유지하는 금액 정책과 후속 검토

SKU 판매가는 **KRW 1원 단위 정수이며 1원 이상**으로 관리한다. 변경 가격은 커밋 이후 새 주문에 적용하고 기존 주문 단가를 유지한다. 현재가만 관리하며 가격 이력·예약 가격은 이번 범위에 추가하지 않는다.

**향후 환불·금액 배분 요구에 따라 정밀도와 반올림·버림·잔여 원 처리 규칙을 재검토해야 한다.** 부분 환불 계산과 원결제 금액의 일치 규칙은 아직 정하지 않았다. SKU 판매가의 하한을 환불 금액의 하한으로 적용하지 않는다.

## 주문서 생성과 가격 확정 시점

사용자는 주문서 화면을 만들 때 주문 데이터를 생성한다고 답했다. 서버가 주문 생성 시 현재 SKU 가격을 읽고 단가 스냅샷을 저장하며, 주문서는 생성된 주문의 금액을 표시한다. 이후 같은 주문의 결제에는 저장된 주문 금액을 사용한다. 예를 들어 19,000원으로 주문을 생성한 뒤 SKU 가격이 21,000원으로 바뀌어도 해당 주문은 19,000원을 유지한다.

따라서 주문서 표시 후 결제 전에 현재 SKU 가격을 다시 적용하거나 재확인을 요구하는 선택 질문은 종료한다. 클라이언트가 보낸 금액을 가격 원본으로 삼는다는 뜻은 아니다. 현행 주문 생성의 `expectedTotalAmount` 비교, 주문서 미리보기와 생성 API의 관계는 Order·Cart 담당과 계약 이행을 검토하며 이번 답변만으로 검사나 필드를 제거하지 않는다.

현행 [OrderCreationService](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderCreationService.java)와 [PaymentGroupCheckoutService](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/PaymentGroupCheckoutService.java)는 주문 저장과 재고 예약을 한 DB 트랜잭션에서 처리한다. 후속 Order 인계에서 사용자도 주문서 생성 시 Pending과 선점을 함께 하기로 확정했음을 확인했다. 같은 주문 생성의 재시도·화면 재진입 식별 규칙은 아직 미결정이다.

## 인계에서 확인한 재고·취소 합의

Inventory 채팅에서 사용자 직접 답변으로 확정된 내용을 보존한다. 두 수량은 SKU 행에 두며 별도 Inventory 모듈·현재 재고 테이블로 다시 분리하지 않는다.

- 재고 10개에서 주문 2개분을 예약하면 `available=8`, `reserved=2`다. 결제 성공 시 `8/0`, 결제 전 취소 또는 확정 실패로 예약을 해제하면 `10/0`이다.
- 결제 전 취소·만료는 예약 수량을 판매 가능 수량으로 돌린다.
- 결제 완료 주문은 **환불 성공해야 취소가 확정**된다. 이 경우 취소 확정 시 복구와 환불 성공 후 복구는 같은 시점이며, 두 정책으로 다시 나눠 묻지 않는다. 이미 결제 때 소진된 수량을 판매 가능 수량에 한 번 복구하므로 예약 해제와 구분한다.
- 현행 [RefundOutcomeService](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundOutcomeService.java)는 환불 성공 시 주문을 `PAID → REFUNDED`로 전이하고 판매 가능 수량을 복구한다. 사용자의 업무상 취소 정의를 주문 enum 통합 승인으로 확대하지 않는다.

### 별도 예약 테이블 없이 Order에서 중복 적용을 막는다

주문 A가 2개, B가 3개를 예약하면 `available/reserved=5/5`다. 결제 전 A 취소 후 `7/3`이 되며, A의 취소 재요청에도 `7/3`을 유지해야 한다. 수량 조건 `reserved >= 2`만으로는 남은 3개가 B의 예약인지 구분하지 못한다. 현재는 Order 상태 전이가 중복 복구를 막고 있으므로 이를 현행 중복 복구 버그로 보고하지 않는다.

| 선택지 | 중복 해제를 막는 주체 | 비용과 조건 |
|---|---|---|
| Order 상태 전이로 통제 | 상태 전이에 성공한 트랜잭션만 SKU 수량을 변경한다. | 추가 예약 테이블이 없다. 모든 예약·확정·해제 경로가 Order의 상태 보호와 같은 DB 트랜잭션을 따라야 한다. |
| SKU 모듈에 주문별 예약 기록 추가 | 예약의 `RESERVED → RELEASED` 전이와 수량 복구를 함께 처리한다. | 현재 수량은 SKU 행에 유지한다. 별도 거래 기록·상태·수량 일치 검증과 갱신이 추가된다. |

사용자는 중복 취소 예를 확인한 뒤 첫 안을 채택했다. **별도 예약 테이블 없이 Order 상태 변경에 성공한 처리만 SKU 수량을 변경하고 두 변경을 같은 DB 트랜잭션으로 커밋한다.** 두 번째 안과 이전 Inventory 담당의 권고는 비교 기록이며 구현 대상이 아니다. SKU 수량 조건만으로 주문별 멱등성을 보장한다고 설명하지 않는다.

이 결정은 부분 환불 건별 적용 여부까지 주문 전체 상태 하나로 판별할 수 있다는 뜻은 아니다. Order 채팅에서 부분 환불 지원이 확정됐으므로 환불 건·대상별 중복 적용 방지와 SKU 복구를 함께 묶는 상세가 필요하다. 기존 환불 기록 활용 여부와 요청 단위는 Order가 설계하며 별도 재고 예약 테이블을 다시 추가하지 않는다.

## 다른 담당에서 확인한 합의와 후속 검토

2026-11-13 Product·Cart·Order 담당의 사용자 발언 근거 인계를 확인했다. 아래는 해당 채팅의 합의이며 현재 코드의 실행 결과가 아니다.

- Product: 옵션 없는 상품도 기본 SKU를 둔다. 상품 기본정보 선저장, SKU 생성 시 가격·초기 재고 필수, 초기 재고 0 허용을 확정했다. Product 대표 이미지 관계를 포함한 DB FK 미사용에 맞춰 새 Product–SKU는 논리 참조로 설계한다. 기존 Order 등 FK 이행은 각 담당과 조율한다.
- Product: 비정형 JSON·Catalog 관리 방향을 선택했지만 저장 위치·검증·변경 연계는 [FigJam 후속 이슈](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=448-1280)로 보류했다. 후속 답변으로 Product 전체 판매허용과 SKU 개별 판매 상태의 분리를 확정했다. Product에 품절 상태를 중복 저장하지 않는다.
- Cart: 담기만으로 예약하지 않는다. 상품별 묶음 표시·SKU별 조작, 결제 성공 후 선택 항목 정리를 확정했다. 재진입·편집 제한·주문 연결 방식은 아직 제안이다.
- Order: 주문서 생성 시 Pending·선점·스냅샷, Pending 10분 만료 뒤 실패·해제, 늦은 성공으로 주문을 되살리지 않고 Payment에 실패한 주문임을 알리는 방향이 확정됐다. 이미 해제한 재고를 늦은 성공이나 보상 환불 때 다시 복구하지 않는다. 현행 코드의 15분과 PAYMENT_CONFIRMING 만료 제외를 새 목표 정책으로 유지하지 않는다. 경합·통지·보상 계약은 Order 협의 사항이다.
- Order: 부분 환불 성공 때 해당 취소를 확정하고 재고를 한 번 복구한다. 부분 수량 여부·식별자·적용 기록의 상세는 미결정이다. 쿠폰 정책은 Order 기존 합의를 보존하며 SKU 범위를 확대하지 않는다.

## 실제 코드 근거와 구현 시 주의점

조사 기준은 `cc91c09b753a12a352bda65481210e264d3e88b0`이다. 현행 구현은 아직 SKU 통합 모델이 아니다.

- [Sales](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/entity/Sales.java)는 상품별 가격·판매 상태를 보관하고, [SalesStock](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/entity/SalesStock.java)은 별도 행에 재고를 보관한다.
- [SalesInfoEventTracker.watch](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/event/SalesInfoEventTracker.java)는 가격·상태·재고 변경 전에 재고 행과 판매 행을 순서대로 잠근다. 현행 테이블 분리를 가격·재고 변경의 독립 실행 근거로 삼을 수 없다.
- [SalesStockJpaRepository](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/repository/SalesStockJpaRepository.java)는 수량 조건과 재고 증감을 하나의 UPDATE로 수행한다. 통합 후에도 수량 검증과 변경 사이에 틈이 생기지 않게 해야 한다.
- 가격을 바꾸려고 읽었던 SKU 전체를 나중에 저장하면 그사이 바뀐 재고를 덮어쓸 위험이 있다. 변경 필드만 갱신하는 방식과 버전·잠금·영속성 컨텍스트의 일관성을 구현 전에 정한다.
- 가격 변경과 주문·재고 선점의 순서, 여러 SKU를 잠그는 순서, 재시도·이벤트 기록·롤백은 실제 트랜잭션 테스트가 필요하다. 이 문서는 그 검증 결과가 아니다.

## 남은 합의와 이행

- SKU 생성·삭제 호출 및 트랜잭션, 옵션 JSON 연계와 생성 중복 정의. 가격·초기 재고 필수, 0 재고 허용과 기본 SKU 필요성은 재질문하지 않는다.
- 신규 READY·Product sale_enabled=false는 확정했고 품절 표시/이행·전체 스위치 결합의 동시성을 검토한다. 별도 예약 테이블 없음과 환불 성공 후 취소 확정 원칙은 유지한다.
- 부분 환불 건별 중복 적용 방지, 만료·늦은 결제 성공·보상 환불의 복구 중복 방지 상세.
- 주문서 생성 시 가격 확정 흐름에 맞춘 기존 API 계약 이행, 생성 재시도·재진입 처리와 동시 가격 변경의 잠금 순서.
- 기존 Sales ID를 기본 SKU ID로 보존하는 이행의 참조·버전 검증과 API·이벤트·Catalog 호환성.
- Product·Order·Cart와의 공유 계약 및 적용 순서. SKU 엔티티·마이그레이션·가격·재고 구현은 SKU 담당 하나로 모으며, 상세 설계와 수용 기준 합의 전에는 구현을 시작하지 않는다.

FigJam의 기존 가격·재고 명령과 이벤트 노드는 삭제하지 않고 SKU 영역으로 묶었다. Product 공통 spec은 Product 담당이 소유하고, Order·Cart·Product·Inventory의 다른 사용자 합의를 이 결정으로 초기화하지 않는다.

## 2026-11-13 통합 검토의 기술 조정

이 절은 코디네이터가 조정한 구현 경계다. 사용자 업무 정책이나 구현 착수 승인과 구분한다. 원본은 `/tmp/commercecore-module-handoff-20261113/통합-경계-1차-결정.md`, 신규 판매 준비의 사용자 근거는 같은 디렉터리의 `신규-판매준비-확정.md`다.

- Product·Cart 항목·SKU의 BIGINT/Java Long을 유지하고 기존 Sales ID를 기본 SKU ID로 보존한다. 주문 UUID 의사를 다른 ID 변경으로 확대하지 않는다. 충돌/매핑 불가가 실제로 발견되면 조정한다.
- SKU #334는 sales-info 및 Catalog 다중 SKU 판매 사본의 최소 호환 변경을 소유한다. Product #333은 Product 제어/삭제 이벤트와 Product 사본을 소유한다. Catalog 상품 한 행에 여러 SKU가 덮어써지는 현재 키 구조를 유지하지 않는다.
- Core API YAML 최종 편집자는 Order #331이고 각 담당이 변경 조각을 제공한다. Core RefundIntake/Query/Outcome도 Order가 조정한다. 마이그레이션 번호와 네 PR의 base/순서는 전체 검토 후 배정한다.
- 단일값 API는 기존 단일 SKU 의미와 신규 SKU 계약을 병행하는 A 방향을 통합 검토 기준으로 정했다. [실행 계획의 호환안](../exec-plans/active/334-sku-module/README.md#기존-단일값-api-병행-호환과-남은-결정)에 실제 소비자 제약과 비교 대안을 기록했다. 409·새 가격 정렬·다중 SKU 활성화 경계는 아직 결정 전이며 대표가·최저가·합산 재고 정책은 추가하지 않는다.
- Catalog API 판매/SKU 구간의 최종 편집은 SKU가 맡고 Product는 변경 조각을 제공한다. Live는 기존 조회 어댑터 계약 호환 조사까지만 SKU 범위에 포함한다. SKU별 방송 노출과 방송 업무/API 확대는 별도 결정하며 이번 조사를 구현 승인으로 해석하지 않는다.

SKU 담당의 마지막 FigJam 쓰기는 424:1279이고 이후 편집은 코디네이터에게 인계했다. 신규 판매 준비 확정의 같은 spec 반영·본문 재조회는 코디네이터의 후속 보고이며 SKU 담당의 새 검증으로 기록하지 않는다.


## 2026-11-14 기술안 채택과 구현 경계

후속 구현 지시에서 READY/ON_SALE/PRIVATE를 수동 의도로 저장하고 ON_SALE+available=0을 기존 외부 SOLD_OUT으로 파생하는 기술안을 채택했다. 앞선 품절 표현 검토 문구는 이 절로 구체화한다. READY는 생성 전용이며 판매 시작 시0재고도 허용하고, READY 복귀는 추가하지 않는다. Product 전체 스위치는 SKU 의도·수량을 덮어쓰지 않는다.

기존 단일값 HTTP 호환에서0개는 미등록,1개는 기존 응답,2개 이상은 Core409/Catalog503으로 구분한다. 이는 대표가·최저가·합산재고를 임의 채택하지 않기 위한 기술적 경계이며 공개 다중 SKU 생성/조회·옵션선택·대표가·가격 정렬을 완성한 것이 아니다. 내부0..N조회·SKU별 사본 저장·거래 수량 포트는 구현한다. 새 가격 이력·예약 테이블·옵션 JSON 저장 정책은 추가하지 않는다.

V102/V3와 선행 Product→SKU→Cart→Order 순서는 코디네이터의 기술 배정이다. 기존 마이그레이션은 수정하지 않으며 Sales ID와 과거 거래 참조·가격/재고/버전을 보존한다. 운영 배포는 승인 범위에 없다. 실제 구현·환경별 결과와 미완 범위는 [실행 계획](../exec-plans/active/334-sku-module/README.md)에서 구분한다.
