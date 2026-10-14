package com.shoppinglive.commerce.sales.entity;

import com.shoppinglive.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * 판매 재고.
 *
 * <p>{@code sales_info} 와 1:1 관계이며 shared PK/FK 를 사용한다 ({@code sales_info_id} 가 PK 이자 FK).
 *
 * <p><b>중요:</b> 실제 재고 조정은 이 엔티티의 setter · 도메인 메서드로 하지 않고, {@link
 * com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository} 의 조건부 UPDATE 네이티브 쿼리로만 수행한다.
 * Java {@code if (available >= qty)} check-then-act 갭 에서 초과 판매가 발생하기 때문. 이 클래스의 getter 는 조회·응답 목적만.
 *
 * <p>{@link Persistable} 구현 이유: shared PK 라 기본 생성자로 `salesInfoId` 를 세팅하고 save 하면 Spring Data JPA 가
 * UPDATE 로 오해할 수 있다. `isNew()` 를 통해 신규 여부를 명시적으로 알려 INSERT 를 유도한다.
 */
@Entity
@Table(name = "sales_stock")
public class SalesStock extends AuditedEntity implements Persistable<Long> {

    @Id
    @Column(name = "sales_info_id")
    private Long salesInfoId;

    @Column(name = "available", nullable = false)
    private Integer available;

    @Column(name = "reserved", nullable = false)
    private Integer reserved;

    protected SalesStock() {
        // JPA 전용
    }

    public SalesStock(Long salesInfoId, Integer available, Integer reserved) {
        if (salesInfoId == null) {
            throw new IllegalArgumentException("salesInfoId는 필수입니다.");
        }
        if (available == null || available < 0) {
            throw new IllegalArgumentException("available은 0 이상이어야 합니다: " + available);
        }
        if (reserved == null || reserved < 0) {
            throw new IllegalArgumentException("reserved는 0 이상이어야 합니다: " + reserved);
        }
        this.salesInfoId = salesInfoId;
        this.available = available;
        this.reserved = reserved;
    }

    @Override
    public Long getId() {
        return salesInfoId;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }

    public Long getSalesInfoId() {
        return salesInfoId;
    }

    public Integer getAvailable() {
        return available;
    }

    public Integer getReserved() {
        return reserved;
    }
}
