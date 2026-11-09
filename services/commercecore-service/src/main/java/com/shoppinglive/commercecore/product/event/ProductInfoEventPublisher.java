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
    public static final String TOPIC = "commerce.product-info.v1";
    public static final String EVENT_TYPE = "PRODUCT_INFO_CHANGED";
    private final EventOutbox outbox;
    private final ImageUrlResolver images;

    public ProductInfoEventPublisher(EventOutbox outbox, ImageUrlResolver images) {
        this.outbox = outbox;
        this.images = images;
    }

    public void publish(Product product) {
        var payload =
                new ProductInfoChangedEvent(
                        product.getId(),
                        product.getName(),
                        product.getDescription(),
                        product.getCategory(),
                        product.getMainImageId(),
                        images.urlOf(product.getMainImageId()),
                        product.getSellerId(),
                        product.getVersion(),
                        product.getCreatedAt(),
                        product.getUpdatedAt());
        outbox.append(
                TOPIC,
                EventEnvelope.create(
                        EVENT_TYPE,
                        1,
                        "commercecore-service",
                        Long.toString(product.getId()),
                        Instant.now(),
                        payload));
    }
}
