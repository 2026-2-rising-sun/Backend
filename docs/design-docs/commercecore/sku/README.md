# SKU 모듈 구현과 목표 설계

상태: 내부 기반 구현 중. 기준은 `cc91c09b753a12a352bda65481210e264d3e88b0`에서 진행하는 #334 구현이다. 외부 다중 SKU 노출·가격 정렬·기존 Sales 쓰기 경로 전환은 완료 전이다.

- [ERD 원본](ERD.md) · [ERD HTML](ERD.html)
- [판매 상태·수량 원본](상태관리도.md) · [상태 HTML](상태관리도.html)
- [결정과 이유](../../../adr/0334-sku-price-inventory.md)
- [실행 계획](../../../exec-plans/active/334-sku-module/README.md)
- [현재 CommerceCore 전체 모델](../../services/commercecore/erd.md) · [서비스 문서](../../services/commercecore/README.md)

이 폴더는 #334 목표와 구현 진행 상태를 설명한다. 현재 서비스 문서는 기존 Sales 경로를 포함한다. 두 위치에서 정책을 따로 결정하지 않고 ADR의 확정 정책과 실제 코드에 맞춰 갱신한다. HTML은 같은 폴더의 MD에서 생성하며 외부 서버·CDN 없이 읽는다.

## 내부 포트

[SkuService](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuService.java)는 `findBySkuIds`·`findByProductId`, `lockForOrder`, `create`, `changePrice`·`changeSaleStatus`·`adjustAvailable`, `reserve`·`consume`·`release`·`restock`을 제공한다.

조회는 예약하지 않는다. 거래 잠금과 모든 변경은 호출자의 활성 쓰기 트랜잭션을 요구한다. Product 검증·Order 전이/환불 적용 보호·SKU 수량·Outbox를 같은 트랜잭션으로 묶는다. Product 전체 판매허용은 Product 소유이며 새로운 거래 전에 확인한다. 기존 주문 소진·해제·환불 복구에는 신규 판매허용을 다시 요구하지 않는다.

## API와 이행 상태

기존 `/v1/sales` REST 경로는 아직 기존 Sales 구현을 사용한다. 새 SKU 내부 포트만으로 기존 외부 API가 전환됐다고 보지 않는다. Core API 최종 편집자는 Order이고 SKU 담당이 실제 변경 조각을 제공한다. Catalog API 판매/SKU 구간은 SKU가 맡는다.

V102는 기존 Sales ID·가격·재고·버전을 SKU로 복사하는 확장 단계다. 소비자 연결 전에 이 중간 상태를 독립 배포하거나 두 원본에 병행 쓰지 않는다. V102/V3 번호와 Product→SKU→Cart→Order 순서는 코디네이터 기술 조정이며 사용자 개별 번호 정책이 아니다. 대표가·최저가·합산 재고·새 가격 정렬·SKU별 방송 노출은 미결정이다.
