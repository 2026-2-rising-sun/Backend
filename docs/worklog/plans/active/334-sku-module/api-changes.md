# SKU 판매 API 반영 조각

Core 원본 최종 편집자는 Order #331이다. 기존 경로·필드·외부 enum은 유지하며 아래 의미와 오류를 원본에 반영한다. 구현은 `SalesController → SkuSalesService → ProductTradingService/SkuService`다. 판매 등록·변경은 실제 Product 원본을 잠금·검증하고 SKU·Outbox만 쓴다.

| 기존 경로 | 실제 의미·오류 |
|---|---|
| POST /v1/sales | SELLER 본인·미삭제 상품에 기본 SKU를 생성한다. 전체 중지 중 준비 가능, 초기 READY/0 reserved. SKU가 이미 하나 이상이면 409. 본인 아님403, 상품 없음·삭제404. 기존 request/response 필드 유지, id는 SKU ID. |
| PATCH /v1/sales/{id}/price | 본인·미삭제 검사 뒤 SKU 행 잠금으로 직렬화한다. 양의 KRW 가격. 기존 주문 단가 불변. 응답 version은 SKU 버전, 클라이언트 expected version 입력 없음. |
| PATCH /v1/sales/{id}/stock | 본인·미삭제 검사 뒤 available만 증감한다. 0 미만 또는 INTEGER 범위 초과409. reserved 변경 없음. |
| PATCH /v1/sales/{id}/status | ON_SALE/PRIVATE만 수동 지정한다. READY 복귀400. ON_SALE과 available=0은 SOLD_OUT 응답이며 READY에서 0재고 판매 시작도 허용한다. 동일 의도는 no-op. SOLD_OUT은 수동 저장·요청 상태가 아니다. |
| GET /v1/sales/{id}/stock | SELLER 본인·미삭제 검사. 없음404/타인403. 응답 salesInfoId는 SKU ID의 호환 필드명. |
| GET /v1/sales?productIds= | 기존 봉투 없는 단일 SKU 배열·최대100·미등록 제외 유지. 조회 대상 중 SKU가2개 이상인 상품이 있으면409, 임의 첫값·최저가·합산재고 없음. |
| GET /v1/seller/products 및 /{id} | 기존 단일 판매 필드에서 다중 SKU 대표값을 만들지 않고409. 페이지 total/offset은 Product 단위, 필터는 페이지 계산 전 SKU 존재/파생 상태 조건으로 적용. 본인·미삭제 내부 listSkus는0..N개를 반환하며 신규 HTTP 목록은 추가하지 않음. |

Catalog 원본은 SKU 담당이 관리한다. product_sales는 SKU별 저장하지만 공개 API의 다중 SKU/대표가/가격 정렬은 미확정이며 기존 단일 SKU 경로를 유지한다. 다중 사본은 단일 판매 응답으로 축약하지 않고 기존 조회 불가503으로 구분한다. 신규 wire 필드·토픽·키를 추가하지 않았다. Live 다중 SKU 노출을 완료했다고 주장하지 않는다.
