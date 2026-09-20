package com.shoppinglive.commerce.payments.infrastructure;

import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 결제 시도 JPA 리포지토리.
 *
 * <p>기본 CRUD. 상태 전이 · Reconciler 스캔은 결제 2·3 이슈에서 조건부 UPDATE 로 추가.
 */
public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {
}
