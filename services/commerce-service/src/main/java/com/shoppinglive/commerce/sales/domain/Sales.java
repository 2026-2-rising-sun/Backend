package com.shoppinglive.commerce.sales.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 판매정보. Shopping 상품 하나에 대한 판매 조건(가격·상태)을 보관한다.
 *
 * <p>스키마는 Flyway {@code V1__commerce_baseline.sql} 의 {@code sales_info} 와 매핑된다.
 *
 * <p><b>동시성 전략:</b>
 * <ul>
 *   <li>가격·상태 등 필드 변경은 JPA {@code @Version} 낙관적 락으로 lost update 방어</li>
 *   <li>재고 변경은 별도 {@code sales_stock} 테이블의 조건부 UPDATE 로 관리 (판매 3)</li>
 * </ul>
 */
@Entity
@Table(name = "sales_info")
public class Sales extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false, unique = true)
    private Long productId;

    @Column(name = "price", nullable = false)
    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SalesStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Sales() {
        // JPA 전용
    }

    public Sales(Long productId, Long price, SalesStatus status) {
        this.productId = productId;
        this.price = price;
        this.status = status;
    }

    /**
     * 판매 가격을 변경한다.
     *
     * <p>이미 생성된 주문의 {@code orders.unit_price} 스냅샷은 이 메서드로 바뀌지 않는다.
     *
     * @param newPrice 새 가격. 양의 정수여야 한다
     * @throws IllegalArgumentException {@code newPrice} 가 {@code null} 이거나 0 이하
     */
    public void changePrice(Long newPrice) {
        if (newPrice == null || newPrice <= 0L) {
            throw new IllegalArgumentException("price must be positive: " + newPrice);
        }
        this.price = newPrice;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getPrice() {
        return price;
    }

    public SalesStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
