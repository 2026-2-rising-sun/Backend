package com.shoppinglive.shopping.image.infrastructure;

import com.shoppinglive.shopping.image.domain.ProductImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** 어떤 상품의 대표 이미지로도 쓰이지 않는 이미지. Product 엔티티가 아직 없어 네이티브 쿼리로 둔다. */
    @Query(value = """
            SELECT pi.* FROM product_image pi
            WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.main_image_id = pi.id)
            """, nativeQuery = true)
    List<ProductImage> findAllNotReferencedByProduct();
}
