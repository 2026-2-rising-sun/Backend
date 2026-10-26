package com.shoppinglive.shopping.product.repository;

import com.shoppinglive.shopping.product.entity.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdempotencyKey(String idempotencyKey);

    Page<Product> findBySellerId(String sellerId, Pageable pageable);

    /** 엔티티를 로딩하지 않고 초기 적재용 상품 ID를 오름차순으로 조회한다. */
    @Query("select p.id from Product p where p.id > :id order by p.id asc")
    List<Long> findIdsAfter(@Param("id") Long id, Limit limit);
}
