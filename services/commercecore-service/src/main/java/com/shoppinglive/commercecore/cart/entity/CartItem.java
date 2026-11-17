package com.shoppinglive.commercecore.cart.entity;

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
                        name = "uk_cart_member_sku",
                        columnNames = {"member_id", "sku_id"}))
public class CartItem extends BaseEntity {
    @Column(name = "member_id", nullable = false, updatable = false, length = 36)
    private String memberId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    // SKU가 없었던 기존 행은 상품 참조를 보존해 삭제 없이 표시한다.
    @Column(name = "sku_id", updatable = false)
    private Long skuId;

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

    public CartItem(String memberId, Long productId, Long skuId, int quantity) {
        this(memberId, productId, quantity);
        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException("skuId가 올바르지 않습니다.");
        }
        this.skuId = skuId;
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

    public Long getSkuId() {
        return skuId;
    }

    public Long getVersion() {
        return version;
    }
}
