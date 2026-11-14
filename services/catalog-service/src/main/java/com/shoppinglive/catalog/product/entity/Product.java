package com.shoppinglive.catalog.product.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Core가 발급한 식별자와 버전을 보관하는 조회 사본. 쓰기는 이벤트 저장소만 수행한다. */
@Entity
@Table(name = "product")
public class Product {
    @Id private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private Long mainImageId;

    @Column(nullable = false, length = 2048)
    private String mainImageUrl;

    @Column(length = 36)
    private String sellerId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ProductCategory category;

    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private boolean saleEnabled = true;

    private Instant deletedAt;

    protected Product() {}

    public Long getId() {
        return id;
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

    public String getMainImageUrl() {
        return mainImageUrl;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isSaleEnabled() {
        return saleEnabled;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
