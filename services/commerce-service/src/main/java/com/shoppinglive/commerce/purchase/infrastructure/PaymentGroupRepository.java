package com.shoppinglive.commerce.purchase.infrastructure;
import com.shoppinglive.commerce.purchase.domain.PaymentGroup;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface PaymentGroupRepository extends JpaRepository<PaymentGroup,Long> {
 Optional<PaymentGroup> findByGroupNumberAndMemberId(String number,String member);
 Optional<PaymentGroup> findByMemberIdAndRequestKey(String member,String key);
 List<PaymentGroup> findByMemberIdAndStatusIn(String member,Collection<OrderStatus> statuses);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select g from PaymentGroup g where g.id=:id")
 Optional<PaymentGroup> lockById(@Param("id") Long id);
 @Query("select g.id from PaymentGroup g where g.status=com.shoppinglive.commerce.orders.domain.OrderStatus.PENDING_PAYMENT and g.expiresAt < :time order by g.expiresAt,g.id")
 List<Long> expired(@Param("time") Instant time,org.springframework.data.domain.Pageable page);
}
