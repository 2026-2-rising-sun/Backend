package com.shoppinglive.catalog.product.repository;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.catalog.product.entity.ProductCategory;
import com.shoppinglive.catalog.product.event.ProductInfoEventMessage;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProductReplicaRepositoryTest {
    private DriverManagerDataSource source;
    private ProductReplicaRepository products;
    private JdbcTemplate jdbc;

    protected String databaseUrl() {
        return "jdbc:h2:mem:product_replica_"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    protected String databaseUser() {
        return "sa";
    }

    protected String databasePassword() {
        return "";
    }

    @BeforeEach
    void prepare() {
        String schema = "product_replica_" + UUID.randomUUID().toString().replace("-", "");
        source = new DriverManagerDataSource(databaseUrl(), databaseUser(), databasePassword());
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        source.setSchema(schema);
        jdbc = new JdbcTemplate(source);
        products = new ProductReplicaRepository(new NamedParameterJdbcTemplate(source));
    }

    @Test
    @DisplayName("최초 version 0을 저장하고 같은 버전과 오래된 snapshot은 무시한다")
    void acceptsInitialVersionZeroAndKeepsNewerSnapshot() {
        assertThat(products.apply(snapshot(0, "처음", null))).isTrue();
        assertThat(products.apply(snapshot(0, "중복 덮어쓰기", ProductCategory.BEAUTY))).isFalse();
        assertThat(products.apply(snapshot(9, "최신", ProductCategory.FASHION))).isTrue();
        assertThat(products.apply(snapshot(3, "역순", null))).isFalse();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT id,name,description,category,main_image_url,seller_id,version FROM product WHERE id=7"))
                .containsEntry("id", 7L)
                .containsEntry("name", "최신")
                .containsEntry("description", "최신 설명")
                .containsEntry("category", "FASHION")
                .containsEntry("main_image_url", "/api/commercecore/v1/product-images/109")
                .containsEntry("seller_id", "seller")
                .containsEntry("version", 9L);
    }

    @Test
    @DisplayName("높은 버전이 최초로 도착하면 이후 version 0이 새 값을 덮어쓰지 않는다")
    void acceptsNewerSnapshotBeforeRegistration() {
        assertThat(products.apply(snapshot(4, "수정 먼저", null))).isTrue();
        assertThat(products.apply(snapshot(0, "등록 나중", null))).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id=7", String.class))
                .isEqualTo("수정 먼저");
    }

    @Test
    @DisplayName("동시에 처음 도착한 여러 버전은 단일 상품의 가장 큰 버전으로 수렴한다")
    void concurrentFirstDeliveryConvergesToLargestVersion() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int n = 0; n < 24; n++) {
                long version = n;
                jobs.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    products.apply(snapshot(version, "상품 " + version, null));
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> job : jobs) job.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=7", Long.class))
                .isEqualTo(23);
        assertThat(jdbc.queryForObject("SELECT name FROM product WHERE id=7", String.class))
                .isEqualTo("상품 23");
    }

    private ProductInfoEventMessage snapshot(long version, String name, ProductCategory category) {
        return new ProductInfoEventMessage(
                7,
                name,
                name + " 설명",
                category,
                100 + version,
                "/api/commercecore/v1/product-images/" + (100 + version),
                "seller",
                version,
                Instant.parse("2026-11-08T00:00:00Z"),
                Instant.parse("2026-11-08T01:00:00Z").plusSeconds(version));
    }

    @Test
    @DisplayName("전체 중지와 삭제는 사본에 반영되고 중복·역순·구형 snapshot이 삭제를 되돌리지 않는다")
    void controlAndDeletionRemainProtectedByVersionAndTombstone() {
        var old = snapshot(0, "처음", null);
        assertThat(products.apply(old)).isTrue();
        assertThat(
                        products.apply(
                                new ProductInfoEventMessage(
                                        old.productId(),
                                        old.name(),
                                        old.description(),
                                        old.category(),
                                        old.mainImageId(),
                                        old.mainImageUrl(),
                                        old.sellerId(),
                                        1,
                                        old.createdAt(),
                                        old.updatedAt(),
                                        false,
                                        null)))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sale_enabled FROM product WHERE id=7", Boolean.class))
                .isFalse();
        Instant deleted = Instant.parse("2026-11-13T00:00:00Z");
        assertThat(
                        products.apply(
                                new ProductInfoEventMessage(
                                        old.productId(),
                                        old.name(),
                                        old.description(),
                                        old.category(),
                                        old.mainImageId(),
                                        old.mainImageUrl(),
                                        old.sellerId(),
                                        2,
                                        old.createdAt(),
                                        old.updatedAt(),
                                        false,
                                        deleted)))
                .isTrue();
        assertThat(products.apply(snapshot(1, "역순", null))).isFalse();
        assertThat(products.apply(snapshot(2, "중복", null))).isFalse();
        assertThat(products.apply(snapshot(3, "제어 필드 없는 구형", null))).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sale_enabled FROM product WHERE id=7", Boolean.class))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT deleted_at FROM product WHERE id=7",
                                        java.time.OffsetDateTime.class)
                                .toInstant())
                .isEqualTo(deleted);
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=7", Long.class))
                .isEqualTo(3);
    }
}
