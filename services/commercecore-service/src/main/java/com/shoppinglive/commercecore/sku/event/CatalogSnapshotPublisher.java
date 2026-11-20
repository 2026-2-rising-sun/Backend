package com.shoppinglive.commercecore.sku.event;

import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 현재 revision과 영속 시각으로 재처리 가능한 전체 스냅샷을 저장한다. 호출자는 SKU 잠금을 보유한다. */
@Component
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class CatalogSnapshotPublisher {
    public static final String TOPIC = "commerce.catalog.snapshots.v1";
    private final JdbcTemplate jdbc;
    private final EventOutbox outbox;

    public CatalogSnapshotPublisher(JdbcTemplate jdbc, EventOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public void metadataChanged(long id) {
        jdbc.update(
                "UPDATE sku SET metadata_product_version=COALESCE((SELECT version FROM product WHERE product.id=sku.product_id),0) WHERE id=?",
                id);
        metadata(id);
    }

    public void metadata(long id) {
        SkuSnapshot payload =
                jdbc.queryForObject(
                        """
            SELECT * FROM sku WHERE id=?
            """,
                        (rs, n) ->
                                new SkuSnapshot(
                                        rs.getLong("id"),
                                        rs.getLong("product_id"),
                                        rs.getLong("metadata_version"),
                                        rs.getLong("metadata_product_version"),
                                        rs.getLong("price"),
                                        "KRW",
                                        rs.getString("option_label"),
                                        rs.getString("product_name_snapshot"),
                                        rs.getString("sale_status"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("metadata_updated_at").toInstant(),
                                        rs.getTimestamp("deleted_at") == null
                                                ? null
                                                : rs.getTimestamp("deleted_at").toInstant()),
                        id);
        append("SkuSnapshotChanged", id, payload);
    }

    public void inventory(long id) {
        InventorySnapshot payload =
                jdbc.queryForObject(
                        "SELECT * FROM sku WHERE id=?",
                        (rs, n) ->
                                new InventorySnapshot(
                                        rs.getLong("id"),
                                        rs.getLong("product_id"),
                                        rs.getLong("inventory_version"),
                                        rs.getInt("available"),
                                        rs.getInt("reserved"),
                                        rs.getTimestamp("inventory_changed_at").toInstant()),
                        id);
        append("InventorySnapshotChanged", id, payload);
    }

    private void append(String type, long id, Object payload) {
        outbox.append(
                TOPIC,
                EventEnvelope.create(
                        type, 1, "commercecore-service", "sku:" + id, Instant.now(), payload));
    }

    public record SkuSnapshot(
            long skuId,
            long productId,
            long metadataVersion,
            long productMetadataVersion,
            long price,
            String currency,
            String optionLabel,
            String productNameSnapshot,
            String saleStatus,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}

    public record InventorySnapshot(
            long skuId,
            long productId,
            long inventoryVersion,
            int available,
            int reserved,
            Instant capturedAt) {}
}
