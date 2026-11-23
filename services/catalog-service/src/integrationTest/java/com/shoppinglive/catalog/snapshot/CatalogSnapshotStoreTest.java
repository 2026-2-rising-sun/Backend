package com.shoppinglive.catalog.snapshot;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.catalog.product.repository.ProductReplicaRepository;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CatalogSnapshotStoreTest {
    protected JdbcTemplate jdbc;
    protected DriverManagerDataSource source;
    protected CatalogSnapshotStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String time = "2026-11-19T01:00:00Z";

    protected String url() {
        return "jdbc:h2:mem:snapshots_"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    protected String user() {
        return "sa";
    }

    protected String password() {
        return "";
    }

    @BeforeEach
    void prepare() {
        source = new DriverManagerDataSource(url(), user(), password());
        String schema = "catalog_b_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        source.setSchema(schema);
        jdbc = new JdbcTemplate(source);
        store = newStore();
    }

    protected CatalogSnapshotStore newStore() {
        return new CatalogSnapshotStore(
                jdbc,
                new ProductReplicaRepository(new NamedParameterJdbcTemplate(source)),
                mapper,
                new DataSourceTransactionManager(source));
    }

    protected ObjectNode product(long version) {
        ObjectNode p = mapper.createObjectNode();
        p.put("productId", 7);
        p.put("metadataVersion", version);
        p.put("name", "상품");
        p.put("description", "설명");
        p.putNull("category");
        p.put("mainImageId", 10);
        p.put("mainImageUrl", "/v1/product-images/10");
        p.putNull("sellerId");
        p.put("createdAt", time);
        p.put("updatedAt", time);
        p.put("saleEnabled", true);
        p.putNull("deletedAt");
        p.put("initialSkuCount", 1);
        return p;
    }

    protected ObjectNode sku(long version) {
        ObjectNode p = mapper.createObjectNode();
        p.put("skuId", 71);
        p.put("productId", 7);
        p.put("metadataVersion", version);
        p.put("productMetadataVersion", 0);
        p.put("price", 4000 + version);
        p.put("currency", "KRW");
        p.putNull("optionLabel");
        p.put("productNameSnapshot", "상품");
        p.put("saleStatus", "ON_SALE");
        p.put("createdAt", time);
        p.put("updatedAt", time);
        p.putNull("deletedAt");
        return p;
    }

    protected ObjectNode inventory(long version, int available) {
        ObjectNode p = mapper.createObjectNode();
        p.put("skuId", 71);
        p.put("productId", 7);
        p.put("inventoryVersion", version);
        p.put("available", available);
        p.put("reserved", 2);
        p.put("capturedAt", time);
        return p;
    }

    protected boolean apply(String type, ObjectNode p) {
        String key =
                (type.equals("ProductSnapshotChanged") ? "product:" : "sku:")
                        + p.get(type.equals("ProductSnapshotChanged") ? "productId" : "skuId")
                                .asText();
        return store.apply(CatalogSnapshotMessage.parse(key, envelope(type, key, p).toString()));
    }

    private ObjectNode envelope(String type, String key, ObjectNode p) {
        ObjectNode e = mapper.createObjectNode();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", type);
        e.put("schemaVersion", 1);
        e.put("source", "commercecore-service");
        e.put("aggregateId", key);
        e.put("occurredAt", time);
        e.set("payload", p);
        return e;
    }

    @Test
    @DisplayName("재고와 SKU가 상품보다 먼저 와도 영속 보관하며 독립 버전으로 수렴한다")
    void preservesInventoryBeforeMetadataAndProduct() {
        apply("InventorySnapshotChanged", inventory(12, 8));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_sales", Long.class)).isZero();
        apply("SkuSnapshotChanged", sku(0));
        apply("ProductSnapshotChanged", product(0));
        apply("SkuSnapshotChanged", sku(3));
        apply("InventorySnapshotChanged", inventory(2, 0));
        assertThat(
                        jdbc.queryForMap(
                                "SELECT price,available,status FROM product_sales WHERE sales_id=71"))
                .containsEntry("price", 4003L)
                .containsEntry("available", 8)
                .containsEntry("status", "ON_SALE");
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id=7", String.class))
                .isEqualTo("상품");
    }

    @Test
    @DisplayName("같은 revision과 원문은 재처리해도 무변경이고 다른 원문은 충돌로 거절한다")
    void validatesSameRevisionPayload() {
        apply("InventorySnapshotChanged", inventory(0, 8));
        assertThat(apply("InventorySnapshotChanged", inventory(0, 8))).isFalse();
        assertThatThrownBy(() -> apply("InventorySnapshotChanged", inventory(0, 9)))
                .isInstanceOf(IllegalArgumentException.class);
        apply("ProductSnapshotChanged", product(0));
        var conflicting = product(0);
        conflicting.put("name", "다른 원문");
        assertThatThrownBy(() -> apply("ProductSnapshotChanged", conflicting))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id=7", String.class))
                .isEqualTo("상품");
    }

    @Test
    @DisplayName("상품과 SKU 삭제는 늦은 재고·높은 생존 버전·재시작으로 부활하지 않는다")
    void keepsDurableDeletionFences() {
        apply("ProductSnapshotChanged", product(0));
        apply("SkuSnapshotChanged", sku(0));
        apply("InventorySnapshotChanged", inventory(0, 8));
        var deleted = product(1);
        deleted.put("saleEnabled", false);
        deleted.put("deletedAt", time);
        apply("ProductSnapshotChanged", deleted);
        apply("InventorySnapshotChanged", inventory(1, 9));
        store = newStore();
        assertThatThrownBy(() -> apply("ProductSnapshotChanged", product(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sale_enabled FROM product WHERE id=7", Boolean.class))
                .isFalse();
        var skuDeleted = sku(1);
        skuDeleted.put("deletedAt", time);
        apply("SkuSnapshotChanged", skuDeleted);
        apply("InventorySnapshotChanged", inventory(2, 10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_sales", Long.class)).isZero();
        assertThatThrownBy(() -> apply("SkuSnapshotChanged", sku(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("다중 소비자의 최초 수신·메타데이터·재고 경합도 각 최신 revision으로 수렴한다")
    void concurrentConsumersConverge() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int n = 0; n < 12; n++) {
                int version = n;
                jobs.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    apply("SkuSnapshotChanged", sku(version));
                                    return null;
                                }));
                jobs.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    apply("InventorySnapshotChanged", inventory(version, version));
                                    return null;
                                }));
            }
            start.countDown();
            for (var job : jobs) job.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForMap("SELECT price,available FROM product_sales WHERE sales_id=71"))
                .containsEntry("price", 4011L)
                .containsEntry("available", 11);
    }

    @Test
    @DisplayName("같은 eventId 재수신은 무변경이며 다른 원문에 재사용하면 거절한다")
    void validatesEventIdOriginalEnvelope() {
        var e = envelope("InventorySnapshotChanged", "sku:71", inventory(0, 8));
        var parsed = CatalogSnapshotMessage.parse("sku:71", e.toString());
        assertThat(store.apply(parsed)).isTrue();
        assertThat(store.apply(parsed)).isFalse();
        e.set("payload", inventory(1, 9));
        assertThatThrownBy(() -> store.apply(CatalogSnapshotMessage.parse("sku:71", e.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT inventory_version FROM catalog_snapshot_state WHERE aggregate_id='sku:71'",
                                Long.class))
                .isZero();
    }

    @Test
    @DisplayName("잘못된 키·버전·출처·필드와 SKU 소속 충돌을 반영하지 않는다")
    void rejectsInvalidEnvelopesAndOwnership() {
        var e = envelope("InventorySnapshotChanged", "sku:71", inventory(0, 8));
        assertThatThrownBy(() -> CatalogSnapshotMessage.parse("sku:71", e.toString() + " {}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogSnapshotMessage.parse("sku:72", e.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        e.put("schemaVersion", 2);
        assertThatThrownBy(() -> CatalogSnapshotMessage.parse("sku:71", e.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        e.put("schemaVersion", 1);
        e.put("source", "payment-service");
        assertThatThrownBy(() -> CatalogSnapshotMessage.parse("sku:71", e.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        var invalid = inventory(0, 8);
        invalid.put("price", 100);
        assertThatThrownBy(() -> apply("InventorySnapshotChanged", invalid))
                .isInstanceOf(IllegalArgumentException.class);
        apply("SkuSnapshotChanged", sku(0));
        var moved = inventory(0, 8);
        moved.put("productId", 8);
        assertThatThrownBy(() -> apply("InventorySnapshotChanged", moved))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
