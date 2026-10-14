package com.shoppinglive.commerce.order.repository;

import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.entity.PaymentGroup;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentGroupRepository extends JpaRepository<PaymentGroup, Long> {
    Optional<PaymentGroup> findByGroupNumberAndMemberId(String number, String member);

    Optional<PaymentGroup> findByMemberIdAndRequestKey(String member, String key);

    List<PaymentGroup> findByMemberIdAndStatusIn(String member, Collection<OrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from PaymentGroup g where g.id=:id")
    Optional<PaymentGroup> lockById(@Param("id") Long id);

    @Query(
            "select g.id from PaymentGroup g where g.status=com.shoppinglive.commerce.order.entity.OrderStatus.PENDING_PAYMENT and g.expiresAt < :time order by g.expiresAt,g.id")
    List<Long> expired(@Param("time") Instant time, org.springframework.data.domain.Pageable page);
}
