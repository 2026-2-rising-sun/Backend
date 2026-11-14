# CommerceCore 판매 상태

<!-- visual: relations -->

기준: #334의 기존 단일 판매 API→SKU 전환과 Product `729d2d5f` 통합. [SKU 모듈 상세](../../../commercecore/sku/README.md)와 같은 정책이다. 실제 검증은 실행 계획·워크로그에서 환경과 건수를 구분한다.

[시각 자료](sales.html)

## 범위와 불변조건

SKU는 판매자의 의도 READY·ON_SALE·PRIVATE를 저장한다. READY는 생성 전용이며 돌아갈 수 없다. SOLD_OUT은 ON_SALE이고 available=0일 때만 외부에 표시하는 호환 enum이다. 판매 시작은 초기 재고0에서도 가능하다. Product 전체 허용·미삭제와 SKU 상태·available을 함께 확인해 신규 주문을 판단한다. Product 전체 중지/재개는 SKU 상태나 수량을 덮어쓰지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | READY | 본인·미삭제 Product 검증 뒤 SKU 생성. available 입력·reserved=0. |
| READY | ON_SALE | 판매자 시작. available=0이면 외부 SOLD_OUT 표시. |
| READY | PRIVATE | 판매자 비공개 요청. |
| ON_SALE | PRIVATE | 판매자 비공개. 예약과 기존 주문 상태는 변경하지 않는다. |
| PRIVATE | ON_SALE | 재공개 요청. available=0이면 외부 SOLD_OUT 표시. |
| ON_SALE 표시 | SOLD_OUT 표시 | 선점/조정으로 available=0. 저장 sale_status는 ON_SALE 유지. |
| SOLD_OUT 표시 | ON_SALE 표시 | 해제·복구·보충으로 available>0. PRIVATE/READY는 자동 공개하지 않는다. |

## 수량과 실패

가격·수량·판매 의도는 SKU 행 잠금과 필드별 조건부 UPDATE로 변경한다. Order가 Product 정렬 공유 잠금 후 SKU 정렬 잠금·단가 스냅샷·선점을 같은 트랜잭션으로 묶는다. 소진·해제·환불 복구에는 Product 신규 판매 조건을 다시 검사하지 않는다. Order 전이/환불 적용 기록이 중복 수량 변경을 막으며 SKU는 주문별 멱등성 원장이 아니다.

표시 가격·available·외부 상태가 바뀐 커밋만 sales-info Outbox와 snapshot_version을 만든다. reserved만 소진하면 표시 이벤트가 없다. Outbox 저장 실패는 SKU 변경과 함께 롤백한다.

## 구현과 검증 근거

- [SkuSalesService](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuSalesService.java)
- [SkuService](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuService.java)
- [SKU 상태 상세](../../../commercecore/sku/상태관리도.md)
- [실행 계획·결과](../../../../exec-plans/active/334-sku-module/README.md)

기존 SalesService/RegistrationService·테이블은 이 브랜치의 전환 전 Order 호환·이력용이다. 판매 HTTP와 Product 판매 조회는 이 경로를 사용하지 않는다. 최종 Order/Cart 통합·실제 브로커 검증은 후행 PR에서 확인하며 이 중간 브랜치를 독립 배포하지 않는다.
