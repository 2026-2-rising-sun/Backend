package com.shoppinglive.shopping.product.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 상품 기본정보. 가격·판매 상태는 Commerce 판매정보가 갖고, 판매정보가 등록되기 전까지는 공개·주문 대상이 아니다.
 *
 * <p>대표 이미지는 {@code @ManyToOne} 이 아니라 id 컬럼으로만 참조한다. product 와 image 모듈을 느슨하게 두고,
 * 존재 확인은 등록 서비스가 한다 (DB FK 가 최종 방어선).
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

    /** 기본정보 수정의 낙관적 락. */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Product() {
    }

    public Product(String name, String description, Long mainImageId, String idempotencyKey) {
        this.name = name;
        this.description = description;
        this.mainImageId = mainImageId;
        this.idempotencyKey = idempotencyKey;
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

    public Long getVersion() {
        return version;
    }
}
