package com.shoppinglive.shopping.image.infrastructure;

import com.shoppinglive.shopping.image.domain.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
}
