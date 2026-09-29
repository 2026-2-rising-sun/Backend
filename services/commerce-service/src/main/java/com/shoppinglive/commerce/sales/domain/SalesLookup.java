package com.shoppinglive.commerce.sales.domain;

/**
 * 판매정보와 재고를 합친 조회 전용 뷰.
 *
 * <p>Shopping 상품 목록·상세와 Live 방송 상품 카드는 상품 하나마다 가격·판매상태·재고를 함께
 * 보여줘야 한다. 그 값들은 {@code sales_info} 와 {@code sales_stock} 두 테이블에 나뉘어 있으므로,
 * 화면이 두 번 묻지 않도록 조회 시점에 한 행으로 합쳐 내보낸다.
 *
 * <p>엔티티가 아니라 record 인 이유: 이 뷰로는 아무것도 수정하지 않는다. 가격 변경은 판매 2,
 * 재고 변경은 {@link com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository}
 * 의 조건부 UPDATE 만 담당한다. 조회용 값을 엔티티로 돌려주면 호출한 쪽에서 상태를 바꿀 수 있는
 * 것처럼 보이므로 일부러 불변 record 로 둔다.
 *
 * @param productId Shopping 상품 식별자. 호출자가 요청에 쓴 값과 같다
 * @param salesId 판매정보 식별자
 * @param price 판매가 (원)
 * @param status 판매 상태
 * @param available 주문 가능 재고. 결제 대기로 잡혀 있는 {@code reserved} 는 빠진 수량이다
 */
public record SalesLookup(
    Long productId,
    Long salesId,
    Long price,
    SalesStatus status,
    Integer available
) {
}
