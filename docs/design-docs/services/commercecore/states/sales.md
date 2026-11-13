# CommerceCore 판매 상태

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](sales.html)

## 범위와 불변조건

판매자가 직접 지정할 대상은 ON_SALE·PRIVATE다. SOLD_OUT에서 재고 없이 ON_SALE을 요청하면 상태를 유지한다. READY에서 재고 없이 시작하면 거절한다. 전이는 이전 상태 조건부 UPDATE로 경쟁 변경을 감지한다. 재고 수량과 외부 표시 상태를 혼동하지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | READY | 판매 등록. 신규 주문 허용 상태는 ON_SALE이다. |
| READY | ON_SALE | 판매자 요청·판매 가능한 재고 존재를 확인한다. |
| READY | PRIVATE | 판매자 비공개 요청. |
| ON_SALE | PRIVATE | 판매자 비공개 요청. 진행 중 주문의 결제와 별도다. |
| ON_SALE | SOLD_OUT | 재고가 0에 도달한 시스템 조건부 갱신. |
| SOLD_OUT | ON_SALE | 재고 복원·증가 후 조건부 재개 또는 재고가 있는 판매자 재개 요청. |
| SOLD_OUT | PRIVATE | 판매자 비공개 요청. |
| PRIVATE | ON_SALE | 재공개 요청 시 재고가 있는 경우. |
| PRIVATE | SOLD_OUT | 재공개 요청 시 재고가 없는 경우의 실제 적용 상태. |

## 구현과 검증 근거

- [SalesService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/service/SalesService.java)
- [SalesStatus.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/entity/SalesStatus.java)
- [SalesJpaRepository.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/repository/SalesJpaRepository.java)
- [SalesStockJpaRepository.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/repository/SalesStockJpaRepository.java)

관련 기존 테스트: [SalesStatusTransitionRuleTest.java](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/sales/entity/SalesStatusTransitionRuleTest.java) · [SalesStatusConcurrencyTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/sales/service/SalesStatusConcurrencyTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
