package com.shoppinglive.catalog.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.catalog.product.event.ProductInfoEventMessage;
import com.shoppinglive.catalog.product.repository.ProductReplicaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** aggregate 행 잠금으로 최초 수신 경합과 두 버전 영역의 원문 검사를 직렬화한다. */
@Repository
public class CatalogSnapshotStore {
    private final JdbcTemplate jdbc;
    private final ProductReplicaRepository products;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public CatalogSnapshotStore(
            JdbcTemplate jdbc,
            ProductReplicaRepository products,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.products = products;
        this.mapper = mapper;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean apply(CatalogSnapshotMessage message) {
        // 최초 행 생성의 중복 오류는 별도 TX에서 롤백해 PostgreSQL 후속 쿼리를 오염시키지 않는다.
        try {
            transactions.executeWithoutResult(
                    status ->
                            jdbc.update(
                                    "INSERT INTO catalog_snapshot_state(aggregate_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM catalog_snapshot_state WHERE aggregate_id=?)",
                                    message.aggregateId(),
                                    message.aggregateId()));
        } catch (DuplicateKeyException conflict) {
            /* 다른 소비자가 같은 aggregate를 먼저 만들었다. */
        }
        return Boolean.TRUE.equals(transactions.execute(status -> applyLocked(message)));
    }

    private boolean applyLocked(CatalogSnapshotMessage m) {
        State state =
                jdbc.queryForObject(
                        "SELECT * FROM catalog_snapshot_state WHERE aggregate_id=? FOR UPDATE",
                        (rs, n) ->
                                new State(
                                        rs.getLong("metadata_version"),
                                        rs.getLong("inventory_version"),
                                        rs.getString("metadata_payload"),
                                        rs.getString("inventory_payload"),
                                        rs.getBoolean("deleted")),
                        m.aggregateId());
        var receipts =
                jdbc.queryForList(
                        "SELECT envelope FROM catalog_snapshot_receipt WHERE event_id=?",
                        String.class,
                        m.eventId());
        if (!receipts.isEmpty()) {
            if (!read(receipts.getFirst()).equals(m.envelope()))
                throw new IllegalArgumentException("eventId를 다른 이벤트 원문에 재사용할 수 없습니다.");
            return false;
        }
        jdbc.update(
                "INSERT INTO catalog_snapshot_receipt(event_id,envelope) VALUES(?,?)",
                m.eventId(),
                m.envelope().toString());
        boolean inventory = m.eventType().equals("InventorySnapshotChanged");
        long previous = inventory ? state.inventoryVersion() : state.metadataVersion();
        String old = inventory ? state.inventory() : state.metadata();
        if (m.version() < previous) return false;
        if (m.version() == previous) {
            if (!read(old).equals(m.payload()))
                throw new IllegalArgumentException("같은 revision의 스냅샷 원문이 다릅니다.");
            return false;
        }
        if (old != null
                && read(old).path("productId").asLong() != m.payload().path("productId").asLong())
            throw new IllegalArgumentException("SKU의 상품 소속을 변경할 수 없습니다.");
        String other = inventory ? state.metadata() : state.inventory();
        if (other != null
                && read(other).path("productId").asLong() != m.payload().path("productId").asLong())
            throw new IllegalArgumentException("메타데이터와 재고의 상품이 다릅니다.");
        boolean deleted = state.deleted() || (!inventory && !m.payload().get("deletedAt").isNull());
        if (state.deleted() && !inventory && m.payload().get("deletedAt").isNull())
            throw new IllegalArgumentException("삭제된 ID는 부활할 수 없습니다.");
        String lane = inventory ? "inventory" : "metadata";
        jdbc.update(
                "UPDATE catalog_snapshot_state SET "
                        + lane
                        + "_version=?,"
                        + lane
                        + "_payload=?,deleted=? WHERE aggregate_id=?",
                m.version(),
                m.payload().toString(),
                deleted,
                m.aggregateId());
        if (m.eventType().equals("ProductSnapshotChanged")) applyProduct(m);
        else {
            JsonNode metadata = inventory ? read(state.metadata()) : m.payload();
            JsonNode quantities = inventory ? m.payload() : read(state.inventory());
            if (deleted)
                jdbc.update(
                        "DELETE FROM product_sales WHERE sales_id=?",
                        m.payload().get("skuId").longValue());
            else if (metadata != null && quantities != null) materialize(metadata, quantities);
        }
        return true;
    }

    private void applyProduct(CatalogSnapshotMessage m) {
        ObjectNode p = m.payload().deepCopy();
        p.set("version", p.remove("metadataVersion"));
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", java.util.UUID.randomUUID().toString());
        root.put("eventType", "PRODUCT_INFO_CHANGED");
        root.put("schemaVersion", 1);
        root.put("source", "commercecore-service");
        String key = p.get("productId").asText();
        root.put("aggregateId", key);
        root.put("occurredAt", Instant.now().toString());
        root.set("payload", p);
        products.apply(ProductInfoEventMessage.parse(key, root.toString()));
    }

    private void materialize(JsonNode m, JsonNode i) {
        long id = m.get("skuId").longValue();
        String sale = m.get("saleStatus").textValue();
        String display =
                sale.equals("ON_SALE") && i.get("available").intValue() == 0 ? "SOLD_OUT" : sale;
        Object[] args = {
            m.get("productId").longValue(),
            m.get("price").longValue(),
            display,
            i.get("available").intValue(),
            m.get("metadataVersion").longValue(),
            Timestamp.from(Instant.parse(m.get("updatedAt").textValue())),
            id
        };
        if (jdbc.update(
                        "UPDATE product_sales SET product_id=?,price=?,status=?,available=?,version=?,updated_at=? WHERE sales_id=?",
                        args)
                == 0)
            jdbc.update(
                    "INSERT INTO product_sales(product_id,price,status,available,version,updated_at,sales_id) VALUES(?,?,?,?,?,?,?)",
                    args);
    }

    private JsonNode read(String raw) {
        if (raw == null) return null;
        try {
            return mapper.readTree(raw);
        } catch (Exception failure) {
            throw new IllegalStateException("저장한 스냅샷을 해석할 수 없습니다.", failure);
        }
    }

    private record State(
            long metadataVersion,
            long inventoryVersion,
            String metadata,
            String inventory,
            boolean deleted) {}
}
