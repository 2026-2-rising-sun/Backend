package com.shoppinglive.catalog.product.repository;

import com.shoppinglive.catalog.product.event.ProductInfoEventMessage;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 상품 사본은 더 큰 버전만 반영한다. 키 충돌 후 재시도 쿼리는 별도 자동 커밋에서 실행한다. */
@Repository
public class ProductReplicaRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductReplicaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean apply(ProductInfoEventMessage p) {
        Map<String, Object> args = new HashMap<>();
        args.put("id", p.productId());
        args.put("name", p.name());
        args.put("description", p.description());
        args.put("category", p.category() == null ? null : p.category().name());
        args.put("imageId", p.mainImageId());
        args.put("url", p.mainImageUrl());
        args.put("seller", p.sellerId());
        args.put("version", p.version());
        args.put("created", Timestamp.from(p.createdAt()));
        args.put("updated", Timestamp.from(p.updatedAt()));
        try {
            if (jdbc.update(
                            """
    INSERT INTO product (id,name,description,category,main_image_id,main_image_url,seller_id,version,created_at,updated_at)
    SELECT :id,:name,:description,:category,:imageId,:url,:seller,:version,:created,:updated
    WHERE NOT EXISTS (SELECT 1 FROM product WHERE id=:id)
    """,
                            args)
                    == 1) return true;
        } catch (DuplicateKeyException conflict) {
            // 같은 상품의 동시 최초 수신. 먼저 커밋한 버전과 비교해 최신 것만 반영한다.
        }
        return jdbc.update(
                        """
   UPDATE product SET name=:name,description=:description,category=:category,main_image_id=:imageId,
    main_image_url=:url,seller_id=:seller,version=:version,created_at=:created,updated_at=:updated
   WHERE id=:id AND version<:version
   """,
                        args)
                == 1;
    }
}
