package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.sales.domain.SalesStatus;

/**
 * 주문서에 표시할 조회 시점 스냅샷 (주문 1).
 *
 * <p><b>재고를 잡아두지 않는다.</b> 여기서 {@code orderable = true} 였더라도 주문 생성(주문 2)
 * 시점에 다시 검증한다. 그 사이 다른 사람이 마지막 재고를 가져갔거나 관리자가 가격을 바꿨을 수
 * 있다. 주문 1 완료 기준의 "주문서 확인 중에는 재고가 줄지 않고, 실제 주문 생성 때만 재고를
 * 확보한다" 가 이 구분이다.
 *
 * @param productId Shopping 상품 식별자
 * @param salesId 판매정보 식별자. 주문 생성 때 서버가 다시 찾으므로 화면이 보관할 필요는 없다
 * @param productName 조회 시점 상품명. 주문이 만들어지면 그 시점 값이 주문에 스냅샷으로 남는다
 * @param unitPrice 조회 시점 단가 (원)
 * @param quantity 확인을 요청한 수량
 * @param totalAmount {@code unitPrice × quantity}
 * @param available 주문 가능 재고 (배정분 제외)
 * @param salesStatus 조회 시점 판매 상태
 * @param orderable 이 수량으로 주문 생성까지 진행할 수 있는가
 * @param reason 주문할 수 없는 사유. {@code orderable} 이면 {@code null}
 */
public record OrderCheckout(
    Long productId,
    Long salesId,
    String productName,
    long unitPrice,
    int quantity,
    long totalAmount,
    int available,
    SalesStatus salesStatus,
    boolean orderable,
    Reason reason
) {

    /**
     * 주문 불가 사유. Shopping 의 {@code purchase-check} 와 같은 이름을 쓰는 값은 같은 뜻이다
     * (화면이 두 API 를 오가며 같은 문구를 쓸 수 있게).
     */
    public enum Reason {
        /** 판매 준비·비공개 등 신규 주문을 받지 않는 상태. */
        NOT_ON_SALE,
        /** 품절이거나, 판매 중이지만 주문 가능 재고가 0. */
        SOLD_OUT,
        /** 요청 수량이 주문 가능 재고보다 많다. */
        EXCEEDS_STOCK
    }

    /**
     * 판매 상태와 재고를 보고 주문 가능 여부를 판정한다.
     *
     * <p>판정 순서가 곧 사용자에게 보여줄 안내의 우선순위다. 비공개 상품에 "재고 부족" 이라고
     * 안내하면 곧 재입고될 것처럼 읽히므로 상태를 먼저 본다.
     */
    public static OrderCheckout of(
        Long productId,
        Long salesId,
        String productName,
        long unitPrice,
        int quantity,
        int available,
        SalesStatus salesStatus) {

        Reason reason = resolveReason(quantity, available, salesStatus);
        return new OrderCheckout(
            productId,
            salesId,
            productName,
            unitPrice,
            quantity,
            unitPrice * quantity,
            available,
            salesStatus,
            reason == null,
            reason);
    }

    private static Reason resolveReason(int quantity, int available, SalesStatus salesStatus) {
        if (salesStatus == SalesStatus.SOLD_OUT) {
            return Reason.SOLD_OUT;
        }
        if (!salesStatus.canAcceptNewOrder()) {
            return Reason.NOT_ON_SALE;
        }
        if (available == 0) {
            // 재고가 0 인데 아직 SOLD_OUT 으로 전이되지 않은 찰나. 사용자에겐 품절이 맞다.
            return Reason.SOLD_OUT;
        }
        if (quantity > available) {
            return Reason.EXCEEDS_STOCK;
        }
        return null;
    }
}
