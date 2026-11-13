# Product 구현 API 변경 조각

구현 기준: Product #333 작업 트리. [Core 조각](api-changes.yaml)은 최종 편집자 Order #331, [Catalog 조각](catalog-api-changes.yaml)은 SKU #334에 전달한다. 공유 원본에 아직 반영되지 않았다면 API 정합성 검사를 통과했다고 보고하지 않는다.

2026-11-13 Order 검토(`msg_3966323c163a`) 후 신규 3매핑·제어 응답 필드와 Catalog reason을 원본에 제한 적용했다. 새 판매자 경로는 루트 BearerAuth를 상속하고 x-required-roles SELLER를 명시한다. 기존 GET/PATCH/parameters를 보존하고 필수 제어 필드를 응답 예시에 추가했다. 구조 검사 6개 서비스/122매핑과 Product 응답 예시 10개 스키마 검사를 통과했다. 최종 공통 Core YAML 편집자는 Order 유지다.

- `PATCH /v1/seller/products/{productId}/sale-state`: SELLER 본인, `{saleEnabled:boolean, version:int64>=0}`, 200 Product envelope. 같은 상태는 version 일치 시 추가 변경/이벤트 없음. 다른 판매자 403, 삭제/미존재 404, 오래된 version 409.
- `DELETE /v1/seller/products/{productId}?version=...`: SELLER 본인, version 필수 쿼리, 204. 첫 삭제는 version 검사, 완료된 본인 삭제 재전송은 원래 version도 204. 상품/SKU/이미지는 물리 삭제하지 않는다. `/v1/seller/products/`의 DELETE는 누락 ID 400.
- Product 등록/기본정보 수정/제어 응답에 `saleEnabled`, `deletedAt` 추가. 판매자 목록·상세와 내부 ProductSnapshot에 `saleEnabled` 추가. 기존 기본정보/가격 응답 필드를 삭제하거나 대표가 정책을 만들지 않는다.
- 삭제 상품은 판매자·내부 일반 조회에서 제외한다. 전체 중지만으로 기존 SKU 상태나 공개 노출 정책을 덮어쓰지 않는다. Catalog에서 전체 중지는 기존 공개 상품의 `purchasable/orderable=false`, `maxQuantity=0`, `reason=SALE_SUSPENDED`로 구분하며 삭제는 공개 목록/상세/구매 확인에서 제외한다.
- Catalog `PurchaseCheck.reason` enum에 `SALE_SUSPENDED` 추가. GET purchase-check와 목록/상세의 설명도 Product 전체 제어와 SKU 조건을 함께 확인하도록 맞춘다. 새 Catalog HTTP 경로는 없다.

조각 적용은 경로 객체/스키마 이름별 병합이다. 기존 Product 경로의 parameters·GET/PATCH·보안 정의를 유지한다. API HTML은 최종 YAML에서 생성한다. 실제 구현/HTTP 검증 결과는 워크로그에서 확인한다.
