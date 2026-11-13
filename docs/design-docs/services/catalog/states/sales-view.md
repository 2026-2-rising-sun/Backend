# Catalog 판매 사본·표시 상태

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](sales-view.html)

## 범위와 불변조건

이는 Core 판매 상태를 변경하는 상태 기계가 아니라 사본 갱신과 화면 표시 규칙이다. 원본 재고를 Catalog가 예약하지 않는다. replica/HTTP 조회 설정과 구매 시점 원본 검증을 구분한다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 행 없음 | 사본 version 0 | backfill은 없는 행만 삽입하며 이미 수신한 이벤트를 덮지 않는다. |
| 행 없음 | 사본 version N | 판매 이벤트의 snapshotVersion으로 최초 사본을 삽입한다. |
| 사본 version N | 사본 version M | M>N인 이벤트만 반영한다. 동일·역순 버전은 갱신하지 않는다. |
| 조회 성공 | 원본 상태 표시 | READY·ON_SALE·SOLD_OUT·PRIVATE를 그대로 매핑한다. 공개 노출은 ON_SALE/SOLD_OUT, 구매 가능 표시는 ON_SALE이다. |
| 조회 결과 없음 | NOT_REGISTERED | 정상 조회에서 판매정보가 없는 경우. |
| 조회 실패 | UNKNOWN | 외부 조회 실패를 품절이나 미등록으로 치환하지 않는다. |

## 구현과 검증 근거

- [ProductSalesRepository.java](../../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepository.java)
- [ProductSalesBackfill.java](../../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/service/ProductSalesBackfill.java)
- [SalesDisplayStatus.java](../../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/client/commercecore/SalesDisplayStatus.java)

관련 기존 테스트: [ProductSalesRepositoryTest.java](../../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepositoryTest.java) · [ProductSalesBackfillTest.java](../../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/sales/service/ProductSalesBackfillTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
