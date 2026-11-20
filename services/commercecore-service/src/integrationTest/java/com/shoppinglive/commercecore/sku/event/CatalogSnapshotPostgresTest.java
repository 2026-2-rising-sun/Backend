package com.shoppinglive.commercecore.sku.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = com.shoppinglive.commercecore.product.support.ProductHttpTestApplication.class,
        properties = {
            "shoppinglive.outbox.relay.enabled=false",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
class CatalogSnapshotPostgresTest
        extends com.shoppinglive.commercecore.product.support.ProductSecuritySupport {
    private static final String SCHEMA =
            "catalog_b_core_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.datasource.username", () -> System.getenv("COMMERCECORE_TEST_POSTGRES_USER"));
        p.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductRepository products;
    @Autowired SkuService skus;
    @Autowired InventorySnapshotBatch batch;
    @Autowired CatalogSnapshotBackfill backfill;
    @Autowired CatalogSnapshotPublisher publisher;

    @Autowired
    com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher productPublisher;

    @Autowired PlatformTransactionManager manager;
    @Autowired OutboxMessageRepository messages;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean EventOutbox outbox;
    private TransactionTemplate tx;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(manager);
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM sku");
        products.deleteAll();
    }

    @AfterEach
    void resetSpy() {
        reset(AopTestUtils.<EventOutbox>getUltimateTargetObject(outbox));
    }

    private long create() {
        var p =
                products.saveAndFlush(
                        new Product("상품", "설명", 1L, UUID.randomUUID().toString(), "seller"));
        return tx.execute(status -> skus.createOnSale(p.getId(), 4000, 10, "상품", null)).skuId();
    }

    private long number(String field, long id) {
        return jdbc.queryForObject("SELECT " + field + " FROM sku WHERE id=?", Long.class, id);
    }

    private long inventoryEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE event_type='InventorySnapshotChanged'",
                Long.class);
    }

    @Test
    @DisplayName("예약·확정·해제·환불은 변경 revision에 합치고 60초 배치가 최신 한 건만 저장한다")
    void coalescesAllStockPorts() {
        long id = create();
        tx.executeWithoutResult(
                s -> {
                    skus.reserve(id, 3);
                    skus.consume(id, 1);
                    skus.release(id, 2);
                    skus.restock(id, 1);
                });
        assertThat(inventoryEvents()).isZero();
        assertThat(number("inventory_version", id)).isEqualTo(4);
        assertThat(number("metadata_version", id)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_outbox WHERE event_type='SkuSnapshotChanged'",
                                Long.class))
                .isEqualTo(1);
        assertThat(batch.publishChanged()).isEqualTo(1);
        assertThat(batch.publishChanged()).isZero();
        assertThat(inventoryEvents()).isEqualTo(1);
        assertThat(number("inventory_published_version", id)).isEqualTo(4);
        assertThat(number("available", id)).isEqualTo(10);
        assertThat(number("reserved", id)).isZero();
    }

    @Test
    @DisplayName("Outbox 실패는 완료표시와 함께 롤백하고 새 배치 인스턴스가 재시도한다")
    void survivesOutboxFailureAndRestart() {
        long id = create();
        doThrow(new IllegalStateException("검증용 Outbox 실패"))
                .when(AopTestUtils.<EventOutbox>getUltimateTargetObject(outbox))
                .append(eq(CatalogSnapshotPublisher.TOPIC), any());
        assertThatThrownBy(batch::publishChanged).isInstanceOf(IllegalStateException.class);
        assertThat(number("inventory_published_version", id)).isEqualTo(-1);
        assertThat(inventoryEvents()).isZero();
        reset(AopTestUtils.<EventOutbox>getUltimateTargetObject(outbox));
        var restarted = new InventorySnapshotBatch(jdbc, publisher, manager, 500);
        assertThat(restarted.publishChanged()).isEqualTo(1);
        assertThat(inventoryEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("다중 배치는 잠긴 SKU를 건너뛰고 발행 중 새 변경은 다음 배치에 남긴다")
    void concurrentBatchAndMutationNeverLoseRevision() throws Exception {
        long id = create();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            if (invocation
                                    .getArgument(1, EventEnvelope.class)
                                    .eventType()
                                    .equals("InventorySnapshotChanged")) {
                                entered.countDown();
                                if (!resume.await(20, TimeUnit.SECONDS))
                                    throw new IllegalStateException("검증 대기 초과");
                            }
                            return invocation.callRealMethod();
                        })
                .when(AopTestUtils.<EventOutbox>getUltimateTargetObject(outbox))
                .append(eq(CatalogSnapshotPublisher.TOPIC), any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(batch::publishChanged);
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(new InventorySnapshotBatch(jdbc, publisher, manager, 500).publishChanged())
                    .isZero();
            var mutation =
                    executor.submit(
                            () -> {
                                tx.executeWithoutResult(s -> skus.reserve(id, 2));
                                return true;
                            });
            resume.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(mutation.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            resume.countDown();
        }
        reset(AopTestUtils.<EventOutbox>getUltimateTargetObject(outbox));
        assertThat(number("inventory_version", id)).isEqualTo(1);
        assertThat(number("inventory_published_version", id)).isZero();
        assertThat(batch.publishChanged()).isEqualTo(1);
        assertThat(inventoryEvents()).isEqualTo(2);
    }

    @Test
    @DisplayName("초기 적재와 재시작 재적재는 같은 revision의 영속 시각과 메타데이터 원문을 보존한다")
    void backfillReusesRevisionAndCaptureTime() throws Exception {
        long id = create();
        batch.publishChanged();
        long product = number("product_id", id);
        String first =
                jdbc.queryForObject(
                        "SELECT payload FROM event_outbox WHERE event_type='InventorySnapshotChanged'",
                        String.class);
        tx.executeWithoutResult(
                s -> jdbc.update("UPDATE product SET version=version+1 WHERE id=?", product));
        backfill.publishProduct(product);
        var payloads =
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE event_type='InventorySnapshotChanged' ORDER BY id",
                        String.class);
        assertThat(mapper.readTree(first).get("payload"))
                .isEqualTo(mapper.readTree(payloads.getLast()).get("payload"));
        var metadata =
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE event_type='SkuSnapshotChanged' ORDER BY id",
                        String.class);
        assertThat(mapper.readTree(metadata.getFirst()).get("payload"))
                .isEqualTo(mapper.readTree(metadata.getLast()).get("payload"));
    }

    @Test
    @DisplayName("상품 최초 발행과 재적재는 DB 시각 정밀도가 같고 삭제 이력도 함께 적재한다")
    void productBackfillPreservesTimestampPrecisionAndTombstone() throws Exception {
        var product =
                products.saveAndFlush(
                        new Product("상품", "설명", 1L, UUID.randomUUID().toString(), "seller"));
        tx.executeWithoutResult(t -> productPublisher.publish(product));
        backfill.publishProduct(product.getId());
        var rows =
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE event_type='ProductSnapshotChanged' ORDER BY id",
                        String.class);
        assertThat(mapper.readTree(rows.getFirst()).path("payload"))
                .isEqualTo(mapper.readTree(rows.getLast()).path("payload"));
        tx.executeWithoutResult(
                t -> {
                    var current = products.findById(product.getId()).orElseThrow();
                    current.softDelete(Instant.now());
                    products.saveAndFlush(current);
                    productPublisher.publish(current);
                });
        backfill.publishProduct(product.getId());
        rows =
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE event_type='ProductSnapshotChanged' ORDER BY id",
                        String.class);
        var deleted = mapper.readTree(rows.getLast()).path("payload");
        assertThat(deleted.path("saleEnabled").booleanValue()).isFalse();
        assertThat(deleted.path("deletedAt").isTextual()).isTrue();
        assertThat(deleted).isEqualTo(mapper.readTree(rows.get(rows.size() - 2)).path("payload"));
    }

    @Test
    @DisplayName("실제 PostgreSQL 업무 TX와 Outbox TEXT에서 세 CDC 봉투를 추출한다")
    void exportsCommittedPostgresOutboxText() throws Exception {
        long id = create();
        long product = number("product_id", id);
        backfill.publishProduct(product);
        var rows =
                jdbc.queryForList(
                        "SELECT event_type,message_key,payload FROM event_outbox WHERE topic=? ORDER BY id",
                        CatalogSnapshotPublisher.TOPIC);
        var directory = java.nio.file.Path.of("../../build/cdc-handoff/postgres");
        java.nio.file.Files.createDirectories(directory);
        java.util.Set<String> types = new java.util.HashSet<>();
        for (var row : rows) {
            String type = (String) row.get("event_type");
            String raw = (String) row.get("payload");
            assertThat(mapper.readTree(raw).path("aggregateId").asText())
                    .isEqualTo(row.get("message_key"));
            java.nio.file.Files.writeString(directory.resolve(type + ".json"), raw);
            types.add(type);
        }
        assertThat(types)
                .containsExactlyInAnyOrder(
                        "ProductSnapshotChanged", "SkuSnapshotChanged", "InventorySnapshotChanged");
    }

    @Test
    @DisplayName("일반 조회와 claim은 CDC 행을 모두 배제하고 다른 알림 토픽은 계속 가져온다")
    void relayPathsExcludeOnlyCatalogTopic() {
        create();
        tx.executeWithoutResult(
                s ->
                        outbox.append(
                                "commerce.payment-group.v1",
                                EventEnvelope.create(
                                        "OrderExpired",
                                        1,
                                        "commercecore-service",
                                        "order:1",
                                        Instant.now(),
                                        Map.of("id", 1))));
        var pending =
                messages.findPendingExcluding(
                        List.of(CatalogSnapshotPublisher.TOPIC), PageRequest.ofSize(100));
        assertThat(pending)
                .isNotEmpty()
                .allMatch(m -> !m.getTopic().equals(CatalogSnapshotPublisher.TOPIC));
        var leases =
                new OutboxLeaseStore(
                        jdbc,
                        manager,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        List.of(CatalogSnapshotPublisher.TOPIC));
        for (int n = 0; n < pending.size(); n++) {
            var claim = leases.claim();
            assertThat(claim).isNotNull();
            assertThat(claim.topic()).isNotEqualTo(CatalogSnapshotPublisher.TOPIC);
            assertThat(leases.published(claim)).isTrue();
        }
        assertThat(leases.claim()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_outbox WHERE topic=? AND published_at IS NULL AND attempts=0",
                                Long.class,
                                CatalogSnapshotPublisher.TOPIC))
                .isEqualTo(1);
    }
}
