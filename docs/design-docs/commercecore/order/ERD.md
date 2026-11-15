# Order 독립 도메인과 물리 매핑

<!-- visual: relations -->

[상세설계](설계.md) · [상태](상태관리도.md) · [Core 전체 ERD](../../services/commercecore/erd.md)

현재 PR은 CommerceCore 독립 모델이다. Payment V4/제어테이블/종료통지/COMPENSATION은 제외했고 신규 공개 주문서·금전어댑터는 후속이다. 과거 완성도표는 보존ref e08b8aea에 있다.

작은 화면에서는 도표 안을 좌우로 이동해 양쪽 노드를 확인한다. 관련 항목 필터로 관계 수를 줄일 수 있다.

## 관계

| 출발 | 도착 | 조건·참조 |
|---|---|---|
| 논리 Order | payment_group | 기존 BIGINT PK·물리명·번호 보존. 내부 도메인1은600초, 공개legacy0은기존설정15분 |
| 논리 OrderItem | orders | 1:N 항목, 생성당시SKU·상품명·수량·단가·할인·Cart출처 |
| orders | SKU | sku_id 우선, 과거 sales_info_id 호환. 가격·재고 현재원본 |
| orders | Product 불변소유자 | 신규 seller_id_snapshot 보존, 과거NULL의새환불접수만삭제포함내부소유자조회 |
| 환불계산 도메인 | OrderRefundAmounts | 단가×수량과잔여실결제액 상한. 신규실행어댑터없음 |

## 물리 이행

V104는 정책구분/항목스냅샷/과거거래출처CHECK/환불대상수량/쿠폰사용출처를 확장한다. order_payment_closure와refund_request의신규목적·종료참조는 제외한다. 기존 PK/FK/Cart참조·금액·저장기한은재작성하지않고 새로운 모듈간FK도추가하지않는다. 이미보존V104를적용한환경은별도이행없이다시적용할수없으며배포가능이라주장하지않는다.

옵션 공급은보류라null이다. 과거판매자를현재표시상품값으로추정하거나완료된NULL환불행을자동보정하지않는다.
