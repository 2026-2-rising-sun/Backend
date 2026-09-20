package com.shoppinglive.commerce.sales.infrastructure;

import com.shoppinglive.commerce.sales.domain.Sales;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 판매정보 JPA 리포지토리.
 *
 * <p>재고 변경은 별도 {@code SalesStockJpaRepository} 에서 조건부 UPDATE 네이티브 쿼리로 관리한다
 * (판매 3 이슈). 여기서는 JPA 기본 CRUD 만 제공한다.
 */
public interface SalesJpaRepository extends JpaRepository<Sales, Long> {
}
