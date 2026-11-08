package com.shoppinglive.catalog.product.event;

import com.shoppinglive.catalog.product.repository.ProductReplicaRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProductInfoEventListener {
    private final ProductReplicaRepository products;

    public ProductInfoEventListener(ProductReplicaRepository products) {
        this.products = products;
    }

    @KafkaListener(topics = ProductInfoEventMessage.TOPIC)
    public void consume(ConsumerRecord<String, String> record) {
        products.apply(ProductInfoEventMessage.parse(record.key(), record.value()));
    }
}
