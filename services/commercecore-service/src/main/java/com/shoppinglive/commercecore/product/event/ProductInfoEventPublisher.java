package com.shoppinglive.commercecore.product.event;

import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.storage.ImageUrlResolver;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 상품 저장과 같은 트랜잭션에 전송할 전체 상태를 기록한다. */
@Component
public class ProductInfoEventPublisher {
    public static final String TOPIC = "commerce.catalog.snapshots.v1";
    public static final String EVENT_TYPE = "ProductSnapshotChanged";
    private final EventOutbox outbox;
    private final ImageUrlResolver images;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ProductInfoEventPublisher(
            EventOutbox outbox,
            ImageUrlResolver images,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.images = images;
    }

    public record ProductSnapshot(
            long productId,
            String name,
            String description,
            com.shoppinglive.commercecore.product.entity.ProductCategory category,
            long mainImageId,
            String mainImageUrl,
            String sellerId,
            long metadataVersion,
            Instant createdAt,
            Instant updatedAt,
            boolean saleEnabled,
            Instant deletedAt,
            Integer initialSkuCount) {}

    private record Times(Instant createdAt, Instant updatedAt, Instant deletedAt) {}

    public void publish(Product product) {
        // flush 후 DB 정밀도의 시각을 사용해 같은 버전 재적재 원문을 보존한다.
        var times =
                jdbc.queryForObject(
                        "SELECT created_at,updated_at,deleted_at FROM product WHERE id=?",
                        (rs, n) ->
                                new Times(
                                        rs.getTimestamp(1).toInstant(),
                                        rs.getTimestamp(2).toInstant(),
                                        rs.getTimestamp(3) == null
                                                ? null
                                                : rs.getTimestamp(3).toInstant()),
                        product.getId());
        var payload =
                new ProductSnapshot(
                        product.getId(),
                        product.getName(),
                        product.getDescription(),
                        product.getCategory(),
                        product.getMainImageId(),
                        images.urlOf(product.getMainImageId()),
                        product.getSellerId(),
                        product.getVersion(),
                        times.createdAt(),
                        times.updatedAt(),
                        product.isSaleEnabled(),
                        times.deletedAt(),
                        product.getInitialSkuCount());
        outbox.append(
                TOPIC,
                EventEnvelope.create(
                        EVENT_TYPE,
                        1,
                        "commercecore-service",
                        "product:" + product.getId(),
                        Instant.now(),
                        payload));
    }
}
