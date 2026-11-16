package com.shoppinglive.commercecore.cart.repository;

import com.shoppinglive.commercecore.cart.entity.CartItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByMemberIdOrderByIdDesc(String memberId);

    Optional<CartItem> findByIdAndMemberId(Long id, String memberId);

    boolean existsByMemberIdAndProductId(String memberId, Long productId);

    boolean existsByMemberIdAndSkuId(String memberId, Long skuId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartItem c where c.id = :id and c.memberId = :memberId")
    Optional<CartItem> lockOwned(@Param("id") Long id, @Param("memberId") String memberId);

    @Modifying(flushAutomatically = true)
    @Query(
            "delete from CartItem c where c.id = :id and c.memberId = :memberId"
                    + " and c.version = :version")
    int deletePurchased(
            @Param("id") Long id,
            @Param("memberId") String memberId,
            @Param("version") Long version);
}
