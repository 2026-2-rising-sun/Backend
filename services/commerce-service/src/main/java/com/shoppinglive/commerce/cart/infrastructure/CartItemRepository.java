package com.shoppinglive.commerce.cart.infrastructure;

import com.shoppinglive.commerce.cart.domain.CartItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByMemberIdOrderByIdDesc(String memberId);
    Optional<CartItem> findByIdAndMemberId(Long id, String memberId);
    boolean existsByMemberIdAndProductId(String memberId, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartItem c where c.id = :id and c.memberId = :memberId")
    Optional<CartItem> lockOwned(@Param("id") Long id, @Param("memberId") String memberId);
}
