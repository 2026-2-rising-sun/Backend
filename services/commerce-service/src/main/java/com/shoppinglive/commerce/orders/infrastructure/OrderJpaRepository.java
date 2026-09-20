package com.shoppinglive.commerce.orders.infrastructure;

import com.shoppinglive.commerce.orders.domain.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 JPA 리포지토리.
 *
 * <p>외부 노출 식별자인 {@code orderNumber} 로 조회하는 파생 쿼리 하나 제공. 만료·취소 시
 * 조건부 UPDATE 는 각 카드 이슈에서 추가한다 (주문 4 · 주문 5).
 */
public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);
}
