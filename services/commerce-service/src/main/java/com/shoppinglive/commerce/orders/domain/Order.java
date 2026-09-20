package com.shoppinglive.commerce.orders.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 주문 엔티티.
 *
 * <p>P1 은 단건 상품 주문 스코프라 {@code order_items} 없이 {@code sales_info_id} 로 참조한다.
 *
 * <p><b>불변 필드 (updatable=false):</b> orderNumber · salesInfoId · quantity · unitPrice ·
 * totalAmount · productNameSnapshot · buyerName · buyerPhone · lookupPasswordHash ·
 * idempotencyKey · expiresAt. 주문 생성 시점의 스냅샷이며 이후 어떤 유스케이스도 이를
 * 바꾸지 않는다 (상품 3 · 판매 2 등 후속 변경은 기존 주문에 영향 없음).
 *
 * <p><b>변경 가능:</b> status · cancelledAt · version. 상태 전이는 서비스 계층의 조건부
 * UPDATE 로 수행한다.
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Column(name = "order_number", nullable = false, unique = true, updatable = false, length = 64)
    private String orderNumber;

    @Column(name = "sales_info_id", nullable = false, updatable = false)
    private Long salesInfoId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private Long unitPrice;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private Long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "buyer_name", nullable = false, updatable = false, length = 64)
    private String buyerName;

    @Column(name = "buyer_phone", nullable = false, updatable = false, length = 32)
    private String buyerPhone;

    @Column(name = "lookup_password_hash", nullable = false, updatable = false, length = 255)
    private String lookupPasswordHash;

    @Column(name = "product_name_snapshot", nullable = false, updatable = false, length = 255)
    private String productNameSnapshot;

    @Column(name = "idempotency_key", updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Order() {
        // JPA 전용
    }

    /**
     * 새 주문을 생성한다 (초기 상태 = {@link OrderStatus#PENDING_PAYMENT}).
     *
     * <p>주문 생성 유스케이스(주문 2)에서 사용한다. 본 이슈(주문 3, 조회) 는 test setup 에서만
     * 호출한다.
     */
    public Order(
        String orderNumber,
        Long salesInfoId,
        int quantity,
        long unitPrice,
        String buyerName,
        String buyerPhone,
        String lookupPasswordHash,
        String productNameSnapshot,
        String idempotencyKey,
        Instant expiresAt) {
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber must not be blank");
        }
        if (salesInfoId == null) {
            throw new IllegalArgumentException("salesInfoId must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPrice <= 0L) {
            throw new IllegalArgumentException("unitPrice must be positive: " + unitPrice);
        }
        if (buyerName == null || buyerName.isBlank()) {
            throw new IllegalArgumentException("buyerName must not be blank");
        }
        if (buyerPhone == null || buyerPhone.isBlank()) {
            throw new IllegalArgumentException("buyerPhone must not be blank");
        }
        if (lookupPasswordHash == null || lookupPasswordHash.isBlank()) {
            throw new IllegalArgumentException("lookupPasswordHash must not be blank");
        }
        if (productNameSnapshot == null || productNameSnapshot.isBlank()) {
            throw new IllegalArgumentException("productNameSnapshot must not be blank");
        }
        this.orderNumber = orderNumber;
        this.salesInfoId = salesInfoId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = unitPrice * quantity;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.buyerName = buyerName;
        this.buyerPhone = buyerPhone;
        this.lookupPasswordHash = lookupPasswordHash;
        this.productNameSnapshot = productNameSnapshot;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Long getSalesInfoId() {
        return salesInfoId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public String getBuyerPhone() {
        return buyerPhone;
    }

    public String getLookupPasswordHash() {
        return lookupPasswordHash;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Long getVersion() {
        return version;
    }
}
