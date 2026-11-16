package com.shoppinglive.commercecore.product.entity;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 상품 표현 정보와 전체 판매 제어. 옵션별 가격·재고·판매 상태는 SKU가 소유한다.
 *
 * <p>대표 이미지는 id 컬럼으로 참조하고 등록·수정 서비스에서 존재를 확인한다. 이미지 파일은 공유될 수 있어 상품 삭제 시 지우지 않는다.
 */
@Entity
@Table(name = "product")
public class Product extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private Long mainImageId;

    /** 등록 재전송 중복 방지 키 (헤더 X-Idempotency-Key). 같은 키의 재요청은 새 상품을 만들지 않는다. */
    @Column(length = 64, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "seller_id", length = 36, updatable = false)
    private String sellerId;

    /** null 이면 미분류. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ProductCategory category;

    @Column(nullable = false)
    private boolean saleEnabled;

    private Instant deletedAt;

    /** 기본정보·전체 제어·삭제의 낙관적 잠금. */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Product() {}

    public Product(String name, String description, Long mainImageId, String idempotencyKey) {
        this(name, description, mainImageId, idempotencyKey, null);
    }

    public Product(
            String name,
            String description,
            Long mainImageId,
            String idempotencyKey,
            String sellerId) {
        this(name, description, mainImageId, idempotencyKey, sellerId, null);
    }

    public Product(
            String name,
            String description,
            Long mainImageId,
            String idempotencyKey,
            String sellerId,
            ProductCategory category) {
        this.name = name;
        this.description = description;
        this.mainImageId = mainImageId;
        this.idempotencyKey = idempotencyKey;
        this.sellerId = sellerId;
        this.category = category;
    }

    /** 검증된 값으로 기본정보를 바꾼다. 실제로 바뀐 값이 있으면 flush 때 version 이 오른다. */
    public void changeBasicInfo(
            String name, String description, Long mainImageId, ProductCategory category) {
        this.name = name;
        this.description = description;
        this.mainImageId = mainImageId;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Long getMainImageId() {
        return mainImageId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSellerId() {
        return sellerId;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isSaleEnabled() {
        return saleEnabled;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void changeSaleEnabled(boolean saleEnabled) {
        this.saleEnabled = saleEnabled;
    }

    public void softDelete(Instant deletedAt) {
        this.saleEnabled = false;
        this.deletedAt = deletedAt;
    }
}
