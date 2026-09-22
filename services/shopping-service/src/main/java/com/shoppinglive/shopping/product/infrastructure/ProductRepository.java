package com.shoppinglive.shopping.product.infrastructure;

import com.shoppinglive.shopping.product.domain.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByIdempotencyKey(String idempotencyKey);
}
