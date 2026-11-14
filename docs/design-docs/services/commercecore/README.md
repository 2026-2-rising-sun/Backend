# CommerceCore

책임: 상품·이미지·판매·재고·쿠폰·장바구니·주문·결제/환불 접수와 결과 반영.

Payment 실행 원본과 분리한다. 회원 ID는 Member 식별자이며 서비스 간 FK는 없다.

기준: Product #333 `729d2d5f`·SKU #334 `1a09aacd`·Cart V103을 통합했다. 판매 HTTP·Product 판매 조회와 Cart 현재가/가용 재고는 SKU 원본을 사용한다. 기존 Order 거래 경로 전환과 후속 이행은 Order 통합에서 검증한다. 공개 다중 SKU/대표가·가격 정렬은 보류이며 운영 배포 결과와 구분한다.

## 문서

- [SKU 모듈 목표·구현 진행 상태](../../commercecore/sku/README.md): V102/V3·내부 거래 포트의 별도 상세. 아래 기존 Sales 설명과 전환 상태를 구분한다.
- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/commercecore-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [Product 상세 ERD·전체 상태](../../commercecore/product/README.md)
- [주문·결제 묶음](states/order.md) · [시각 자료](states/order.html)
- [결제 전달·결과 반영](states/payment.md) · [시각 자료](states/payment.html)
- [환불 접수·결과 반영](states/refund.md) · [시각 자료](states/refund.html)
- [판매 상태](states/sales.md) · [시각 자료](states/sales.html)

## 원본·갱신

[구현](../../../../services/commercecore-service/src/main/java) · [마이그레이션](../../../../services/commercecore-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
