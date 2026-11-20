package com.shoppinglive.catalog.snapshot;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CatalogSnapshotListener {
    private final CatalogSnapshotStore store;

    public CatalogSnapshotListener(CatalogSnapshotStore store) {
        this.store = store;
    }

    @KafkaListener(
            topics = "commerce.catalog.snapshots.v1",
            groupId = "${catalog.snapshots.group-id:catalog-snapshots-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        store.apply(CatalogSnapshotMessage.parse(record.key(), record.value()));
    }
}
