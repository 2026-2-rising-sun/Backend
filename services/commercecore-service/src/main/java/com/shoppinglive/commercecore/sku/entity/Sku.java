package com.shoppinglive.commercecore.sku.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** 단일 SKU 저장 모델. 쓰기는 SkuRepository의 필드별 조건부 SQL로만 수행한다. */
@Entity
@Immutable
@Table(name = "sku")
public class Sku {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SkuSaleStatus saleStatus;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private long snapshotVersion;

    @Column(nullable = false, length = 255)
    private String productNameSnapshot;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Sku() {}
}
