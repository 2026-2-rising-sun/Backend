package com.shoppinglive.commerce.order.entity;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(
        name = "cart_item",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_cart_member_product",
                        columnNames = {"member_id", "product_id"}))
public class CartItem extends BaseEntity {
    @Column(name = "member_id", nullable = false, updatable = false, length = 36)
    private String memberId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false)
    private Integer quantity;

    @Version
    @Column(nullable = false)
    private Long version;

    protected CartItem() {}

    public CartItem(String memberId, Long productId, int quantity) {
        this.memberId = UUID.fromString(memberId).toString();
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("productId가 올바르지 않습니다.");
        }
        this.productId = productId;
        changeQuantity(quantity);
    }

    public void changeQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다.");
        }
        this.quantity = quantity;
    }

    public String getMemberId() {
        return memberId;
    }

    public Long getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Long getVersion() {
        return version;
    }
}
