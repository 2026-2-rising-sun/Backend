package com.shoppinglive.commercecore.sku.event;

import com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영자가 범위를 나눠 실행한다. 삭제 행도 재적재하며 기존 revision을 바꾸지 않는다. */
@Service
public class CatalogSnapshotBackfill {
    private final EntityManager entities;
    private final ProductRepository products;
    private final ProductInfoEventPublisher productEvents;
    private final CatalogSnapshotPublisher skuEvents;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public CatalogSnapshotBackfill(
            EntityManager entities,
            ProductRepository products,
            ProductInfoEventPublisher productEvents,
            CatalogSnapshotPublisher skuEvents,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.entities = entities;
        this.products = products;
        this.productEvents = productEvents;
        this.skuEvents = skuEvents;
        this.jdbc = jdbc;
    }

    @Transactional
    public void publishProduct(long productId) {
        var product = products.findById(productId).orElseThrow();
        entities.refresh(product, LockModeType.PESSIMISTIC_READ);
        productEvents.publish(product);
        for (long id :
                jdbc.queryForList(
                        "SELECT id FROM sku WHERE product_id=? ORDER BY id FOR UPDATE",
                        Long.class,
                        productId)) {
            skuEvents.metadata(id);
            skuEvents.inventory(id);
        }
    }
}
