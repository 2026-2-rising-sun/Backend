# CommerceCore

상품·이미지·판매·재고·쿠폰·장바구니·주문을 소유한다. 결제·환불은 접수와 결과 반영을 담당하고, 실행 원본은 Payment가 소유한다. 서비스 간 식별자는 논리 참조이며 다른 서비스 DB와 FK를 연결하지 않는다.

## 전체 데이터 관계

[서비스 전체 ERD](erd.md)에서 모듈 간 연결과 기존 이행 데이터를 확인한다. 모듈별 상세는 아래 문서가 원본이다.

| 모듈 | 업무·상태 설명 | 데이터 모델 |
|---|---|---|
| Product | [상품 제어·거래 검증](product/overview.md) | [ERD](product/erd.md) |
| SKU | [가격·재고·판매 의도](sku/overview.md) | [ERD](sku/erd.md) |
| Cart | [장바구니 선택·성공 정리](cart/overview.md) | [ERD](cart/erd.md) |
| Order | [주문·결제 접수·환불 결과](order/overview.md) | [ERD](order/erd.md) |
| Coupon | [쿠폰 정의·발급·사용](coupon/erd.md) | [ERD](coupon/erd.md) |

## API 명세

| 모듈 | 명세 |
|---|---|
| Product | [MD](product/api.md) · [HTML](product/api.html) |
| SKU | [MD](sku/api.md) · [HTML](sku/api.html) |
| Cart | [MD](cart/api.md) · [HTML](cart/api.html) |
| Order | [MD](order/api.md) · [HTML](order/api.html) |
| Coupon | [MD](coupon/api.md) · [HTML](coupon/api.html) |

API 원본은 [CommerceCore OpenAPI](../../../../contracts/api/commercecore-service.yaml)이며 모듈별 화면은 같은 계약에서 생성한다.

## 서비스 간 흐름과 실행 범위

[주문·결제](../../flows/order-payment.md) · [환불](../../flows/refund.md) · [Catalog 동기화](../../flows/catalog-sync.md).

현재 구현·재설계 목표와 미검증 범위는 각 흐름에서 구분한다. 공개 다중 SKU·대표가·옵션 정책을 이 문서 정리에서 새로 확정하지 않는다. 최신 실행 결과와 남은 통합 작업은 [워크로그](../../../worklog/README.md)에서 확인한다.

[Java 구현](../../../../services/commercecore-service/src/main/java) · [마이그레이션](../../../../services/commercecore-service/src/main/resources/db).
