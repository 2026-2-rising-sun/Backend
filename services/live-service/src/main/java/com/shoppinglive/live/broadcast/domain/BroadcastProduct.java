package com.shoppinglive.live.broadcast.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 방송-상품 연결. productId 는 Shopping 식별자, salesId 는 Commerce 식별자이며 같다고 가정하지 않는다.
 * 가격·재고·이름은 복제 저장하지 않고 조회 시점에 외부에서 읽는다.
 */
@Entity
@Table(name = "broadcast_product",
    uniqueConstraints = @UniqueConstraint(name = "uk_broadcast_product",
        columnNames = {"broadcast_id", "product_id"}))
public class BroadcastProduct extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "broadcast_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_broadcast_product_broadcast"))
    private Broadcast broadcast;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sales_id", nullable = false)
    private Long salesId;

    @Column(nullable = false)
    private int position;

    protected BroadcastProduct() {
    }

    public BroadcastProduct(final Broadcast broadcast, final Long productId, final Long salesId,
                            final int position) {
        this.broadcast = broadcast;
        this.productId = productId;
        this.salesId = salesId;
        this.position = position;
    }

    public Broadcast getBroadcast() {
        return broadcast;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getSalesId() {
        return salesId;
    }

    public int getPosition() {
        return position;
    }

    public void moveTo(final int position) {
        this.position = position;
    }
}
