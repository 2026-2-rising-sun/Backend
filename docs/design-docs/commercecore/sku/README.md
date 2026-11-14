# SKU 모듈 구현과 목표 설계

상태: 기존 단일 SKU 판매 API·상품 조회를 SKU 원본으로 전환하고 통합 검증 중이다. Product `729d2d5f`를 일반 merge했다. 공개 다중 SKU 생성·조회/옵션 선택·대표가·가격 정렬은 보류다. 운영 배포 결과와 구분한다.

- [ERD 원본](ERD.md) · [ERD HTML](ERD.html)
- [판매 상태·수량 원본](상태관리도.md) · [상태 HTML](상태관리도.html)
- [결정과 이유](../../../adr/0334-sku-price-inventory.md)
- [실행 계획](../../../exec-plans/active/334-sku-module/README.md)
- [현재 CommerceCore 전체 모델](../../services/commercecore/erd.md) · [서비스 문서](../../services/commercecore/README.md)

이 폴더는 #334 목표와 구현 진행 상태를 설명한다. 현재 서비스 문서는 새 판매 API와 전환 전 Order 호환 경로를 구분한다. 두 위치에서 정책을 따로 결정하지 않고 ADR의 확정 정책과 실제 코드에 맞춰 갱신한다. HTML은 같은 폴더의 MD에서 생성하며 외부 서버·CDN 없이 읽는다.

## 내부 포트

[SkuService](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuService.java)는 `findBySkuIds`·`findByProductId`·`findByProductIds`, `lockForOrder`, `create`, `changePrice`·`changeSaleStatus`·`adjustAvailable`, `reserve`·`consume`·`release`·`restock`을 제공한다.

조회는 예약하지 않는다. 거래 잠금과 모든 변경은 호출자의 활성 쓰기 트랜잭션을 요구한다. Product 검증·Order 전이/환불 적용 보호·SKU 수량·Outbox를 같은 트랜잭션으로 묶는다. Product 전체 판매허용은 Product 소유이며 새로운 거래 전에 확인한다. 기존 주문 소진·해제·환불 복구에는 신규 판매허용을 다시 요구하지 않는다.

## API와 이행 상태

기존 `/v1/sales` REST 경로는 SkuSalesService를 통해 SKU·Outbox만 쓴다. Product 본인·미삭제 검증과 잠금 뒤 가격·수량·상태를 변경하며 전체 중지 중에도 준비할 수 있다. 단일 상품 등록은 Product 행 배타 잠금으로 중복 생성을 직렬화한다. 조회·거래 내부 포트는 SKU 원본을 사용한다. 기존 SalesService/RegistrationService와 Sales 테이블은 선행 브랜치의 Order 호환·이력 경로로 남아 있으며 Order #331이 최종 거래 연결을 통합한다. 이 중간 브랜치를 독립 배포하지 않는다. Core API 최종 편집자는 Order이고 SKU 담당이 실제 변경 조각을 제공한다. Catalog API 판매/SKU 구간은 SKU가 맡는다.

V102는 기존 Sales ID·가격·재고·버전을 SKU로 복사하는 확장 단계다. 소비자 연결 전에 이 중간 상태를 독립 배포하거나 두 원본에 병행 쓰지 않는다. V102/V3 번호와 Product→SKU→Cart→Order 순서는 코디네이터 기술 조정이며 사용자 개별 번호 정책이 아니다. 대표가·최저가·합산 재고·새 가격 정렬·SKU별 방송 노출은 미결정이다.


## 단일값 호환과 미완료 흐름

상품 페이지를 먼저 자른 뒤 productIds를 모아 SKU를 한 번에 읽는다. 상태 필터는 Product 페이지 계산 전에 EXISTS로 적용해 SKU 수가 상품 total/offset을 늘리지 않는다. 기존 단일 SKU는 최신 가격·available·품절 파생 표시를 반환한다. 복수 SKU를 기존 단일 응답으로 읽으면 Core409/Catalog503이며 첫 SKU·최저가·합산재고를 임의 선택하지 않는다.

판매자 내부 listSkus는 본인·미삭제 상품의0..N개를 제공한다. 판매자의 다중 SKU 생성/조회 HTTP·고객 옵션 선택·상품별 공개 skus[]·Live 다중 SKU 노출은 아직 구현되지 않았다. 옵션 JSON 저장/중복/전파 상세와 대표 가격 정책은 사용자 후속 결정 사항이다. [실제 API 조각](../../../exec-plans/active/334-sku-module/api-changes.md)과 [검증 결과](../../../exec-plans/active/334-sku-module/README.md)를 함께 본다.
