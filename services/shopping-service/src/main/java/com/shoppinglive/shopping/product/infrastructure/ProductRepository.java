package com.shoppinglive.shopping.product.infrastructure;

import com.shoppinglive.shopping.product.domain.Product;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdempotencyKey(String idempotencyKey);

    /** 최신 등록순 첫 구간. 인덱스 ix_product_created_at_id 순서와 같다. */
    @Query("select p from Product p order by p.createdAt desc, p.id desc")
    List<Product> findLatest(Limit limit);

    /**
     * {@code (createdAt, id)} 가 주어진 위치보다 앞선(오래된) 구간. offset 대신 키셋으로 넘겨 페이지가 깊어져도
     * 비용이 같고, 조회 사이에 상품이 새로 등록돼도 중복·누락이 없다. createdAt 이 같은 상품은 id 로 순서를 정한다.
     */
    @Query("""
            select p from Product p
            where p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)
            order by p.createdAt desc, p.id desc""")
    List<Product> findLatestBefore(@Param("createdAt") Instant createdAt, @Param("id") Long id, Limit limit);
}
