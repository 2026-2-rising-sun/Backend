# Catalog

책임: 상품·판매·쿠폰 공개 조회 사본.

상품·판매·쿠폰 원본의 변경 주체는 CommerceCore다. 사본의 버전과 원본의 확정을 구분한다.

기준: Product V2·SKU V3와 단일 SKU 공개 호환 모델. 통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/catalog-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [판매 사본·표시 상태](overview.md) · [시각 자료](overview.html)

## 원본·갱신

[구현](../../../../services/catalog-service/src/main/java) · [마이그레이션](../../../../services/catalog-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.

현재 수정·환경별 검사와 미답변 정책은 [2026-11-15 SKU 기록](../../../worklog/2026-11-15-sku.md)을 따른다.

## Catalog 판매 사본·표시 상태

<!-- visual: relations -->

기준: Product V2·SKU V3 판매 사본과 현재 공개 조회. 통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다.

[시각 자료](overview.html)

### 범위와 불변조건

이는 Core 판매 상태를 변경하는 상태 기계가 아니라 사본 갱신과 화면 표시 규칙이다. 원본 재고를 Catalog가 예약하지 않는다. replica/HTTP 조회 설정과 구매 시점 원본 검증을 구분한다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 행 없음 | 사본 version 0 | backfill은 없는 행만 삽입하며 이미 수신한 이벤트를 덮지 않는다. |
| 행 없음 | 사본 version N | 판매 이벤트의 snapshotVersion으로 최초 사본을 삽입한다. |
| 사본 version N | 사본 version M | M>N인 이벤트만 반영한다. 동일·역순 버전은 갱신하지 않는다. |
| 조회 성공 | 원본 상태 표시 | READY·ON_SALE·SOLD_OUT·PRIVATE를 그대로 매핑한다. 미삭제 상품의 단일 SKU 공개 노출은 ON_SALE/SOLD_OUT이다. 구매 가능 표시는 saleEnabled=true AND ON_SALE AND available>0이다. 전체 중지는 purchase-check의 SALE_SUSPENDED로 구분한다. |
| 조회 결과 없음 | NOT_REGISTERED | 정상 조회에서 판매정보가 없는 경우. |
| 조회 실패 | UNKNOWN | 외부 조회 실패를 품절이나 미등록으로 치환하지 않는다. |

### 구현과 검증 근거

- [ProductSalesRepository.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepository.java)
- [ProductSalesBackfill.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/service/ProductSalesBackfill.java)
- [SalesDisplayStatus.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/client/commercecore/SalesDisplayStatus.java)

관련 기존 테스트: [ProductSalesRepositoryTest.java](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepositoryTest.java) · [ProductSalesBackfillTest.java](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/sales/service/ProductSalesBackfillTest.java).

현재 전체 중지 중 공개 유지와 재고0 판매 시작 성공은 직접 사용자 답변 대기 중인 기술 동작이다. 이번 수정에서 바꾸거나 사용자 승인으로 재분류하지 않았다. 다중 SKU는 대표값 대신 상세·해당 목록 503이며 [ERD의 단일값 경계](erd.md#단일-sku-공개-호환)를 따른다. 실제 실행 여부는 [최신 워크로그](../../../worklog/2026-11-15-sku.md)에서 확인한다.
