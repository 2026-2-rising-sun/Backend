# Order 독립 도메인과 물리 매핑

<!-- visual: relations -->

[상세설계](overview.md) · [상태](overview.md) · [Core 전체 ERD](../erd.md)

현재 PR은 CommerceCore 독립 모델이다. Payment V4/제어테이블/종료통지/COMPENSATION은 제외했고 새 공개 주문서와 Commerce 소유 중계를 추가했다. 실제 Payment 환불 통합은 후속이다. 과거 완성도표는 보존ref e08b8aea에 있다.

작은 화면에서는 도표 안을 좌우로 이동해 양쪽 노드를 확인한다. 관련 항목 필터로 관계 수를 줄일 수 있다.

## 관계

| 출발 | 도착 | 조건·참조 |
|---|---|---|
| 논리 Order | payment_group | 기존 BIGINT PK·물리명·번호 보존. 신규 생성600초. processing_expires_at은 첫 실행부터별도600초 |
| payment_group | late_approval_refund | Core 로컬 FK. 결제ID/late-{paymentId} 유일키, REQUIRED는 환불 완료가 아님 |
| payment_group | seller_order | seller_id별 할인 후 금액·생성 시 commerce.commission-rate·정렬 순번 보존 |
| payment_group | order_payment_relay | 새 원결제 ID/결제창/승인 사실. 기존 legacy 결제 ID와 구분 |
| payment_group | payment_relay_refund | late-{원결제ID}·REQUESTED→SUCCEEDED/FAILED. 환불 요청 outbox와 같은 TX |
| payment_group | payment_delivery_request | PREPARE/APPROVAL 원문·고정 requestId. legacy PAYMENT/REFUND 전달기와 분리 |
| 논리 OrderItem | orders | 1:N 항목, 생성당시SKU·상품명·수량·단가·할인·Cart출처 |
| orders | SKU | sku_id 우선, 과거 sales_info_id 호환. 가격·재고 현재원본 |
| orders | Product 불변소유자 | 신규 seller_id_snapshot 보존, 과거NULL의새환불접수만삭제포함내부소유자조회 |
| 환불계산 도메인 | OrderRefundAmounts | 단가×수량과잔여실결제액 상한. 신규실행어댑터없음 |

## 물리 이행

V104는 정책구분/항목스냅샷/과거거래출처CHECK/환불대상수량/쿠폰사용출처를 확장한다. order_payment_closure와refund_request의신규목적·종료참조는 제외한다. 기존 PK/FK/Cart참조·금액·저장기한은재작성하지않고 새로운 모듈간FK도추가하지않는다. 이미보존V104를적용한환경은별도이행없이다시적용할수없으며배포가능이라주장하지않는다.

옵션 공급은보류라null이다. 과거판매자를현재표시상품값으로추정하거나완료된NULL환불행을자동보정하지않는다.

V105는 별도 처리기한 컬럼과 늦은 승인 환불 필요 기록을 추가한다. 기존 생성기한·상태·정책0/1/2 확인중 데이터의 null 처리기한은 보존한다. Payment DB/제어행/종료API는 수정하지 않는다. V106의 새 계약 중계는 환불 요청 outbox와 결과 소비를 제공한다. 실제 Payment 환불 소비·결과 생산 검증은 미완이다.
