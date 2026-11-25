package com.shoppinglive.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.support.CronExpression;

/**
 * Catalog DB 와 Nori 가 있는 실제 ES 로 확인한다. {@code CATALOG_TEST_ES_URL} 이 가리키는 ES 의 products_* 인덱스를 지우고
 * 쓴다. DB 는 {@code CATALOG_TEST_POSTGRES_URL} 이 있으면 그 PostgreSQL 의 새 스키마, 없으면 H2 다. 잠금이 DB 시각을 읽는
 * SQL 이 DB 마다 달라 운영과 같은 PostgreSQL 로도 확인한다.
 */
@Tag("elasticsearch")
class FullIndexerTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private RestClient rest;
    private ElasticsearchClient client;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private LockingTaskExecutor locks;
    private MutableClock clock;
    private ElasticsearchProductIndex index;
    private String schema;

    @BeforeEach
    void prepare() throws IOException {
        rest = RestClient.builder(HttpHost.create(System.getenv("CATALOG_TEST_ES_URL"))).build();
        client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
        var source = catalogDatabase();
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        locks =
                new DefaultLockingTaskExecutor(
                        new JdbcTemplateLockProvider(
                                JdbcTemplateLockProvider.Configuration.builder()
                                        .withJdbcTemplate(jdbc)
                                        .usingDbTime()
                                        .build()));
        // 한국 시간 2026-11-25 10:00
        clock = new MutableClock(Instant.parse("2026-11-25T01:00:00Z"));
        deleteProductIndices();
        index = new ElasticsearchProductIndex(client, clock);
    }

    @AfterEach
    void close() throws IOException {
        deleteProductIndices();
        rest.close();
        if (schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private DriverManagerDataSource catalogDatabase() {
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) {
            var h2 =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:full_"
                                    + UUID.randomUUID()
                                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                            "sa",
                            "");
            Flyway.configure().dataSource(h2).load().migrate();
            return h2;
        }
        String user = System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_USER", "postgres");
        String password =
                System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_PASSWORD", "postgres");
        schema = "catalog_full_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url, user, password))
                .execute("CREATE SCHEMA " + schema);
        var postgres =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        Flyway.configure()
                .dataSource(postgres)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        return postgres;
    }

    private void deleteProductIndices() throws IOException {
        var names =
                List.copyOf(
                        client.indices()
                                .get(g -> g.index("products_*").allowNoIndices(true))
                                .result()
                                .keySet());
        if (!names.isEmpty()) client.indices().delete(d -> d.index(names));
    }

    private FullIndexer indexer(ProductSearchSource source) {
        return new FullIndexer(
                named,
                source,
                index,
                locks,
                CronExpression.parse("0 0 4 * * *"),
                SEOUL,
                clock,
                2,
                Duration.ofMinutes(1));
    }

    private FullIndexer indexer() {
        return indexer(new ProductSearchSource(named));
    }

    private void product(long id, String name, long price, String status) {
        Timestamp createdAt = Timestamp.from(Instant.parse("2026-11-24T00:00:00Z"));
        jdbc.update(
                "INSERT INTO product(id,name,description,main_image_id,main_image_url,category,version,created_at,updated_at)"
                        + " VALUES(?,?,'설명',1,'/img','FASHION',0,?,?)",
                id,
                name,
                createdAt,
                createdAt);
        jdbc.update(
                "INSERT INTO product_sales(sales_id,product_id,price,status,available,version,updated_at)"
                        + " VALUES(?,?,?,?,5,0,CURRENT_TIMESTAMP)",
                id * 10,
                id,
                price,
                status);
    }

    private List<Long> search(String query) throws IOException {
        client.indices().refresh(r -> r.index(ElasticsearchProductIndex.ALIAS));
        return client
                .search(
                        s ->
                                s.index(ElasticsearchProductIndex.ALIAS)
                                        .query(q -> q.match(m -> m.field("name").query(query))),
                        Map.class)
                .hits()
                .hits()
                .stream()
                .map(hit -> Long.parseLong(hit.id()))
                .toList();
    }

    private long documents() throws IOException {
        client.indices().refresh(r -> r.index(ElasticsearchProductIndex.ALIAS));
        return client.count(c -> c.index(ElasticsearchProductIndex.ALIAS)).count();
    }

    @Test
    @DisplayName("공개 상품만 새 인덱스에 나눠 넣고 alias를 옮겨 문서 수가 공개 상품 수와 같다")
    void rebuildsPublicProducts() throws IOException {
        product(1, "남성 러닝 운동화", 50000, "ON_SALE");
        product(2, "겨울 니트", 30000, "SOLD_OUT");
        product(3, "여름 반팔 티셔츠", 10000, "ON_SALE");
        product(4, "비공개 상품", 1000, "PRIVATE");
        product(5, "삭제 상품", 1000, "ON_SALE");
        jdbc.update(
                "UPDATE product SET sale_enabled=FALSE, deleted_at=CURRENT_TIMESTAMP WHERE id=5");

        assertThat(indexer().rebuild()).isEqualTo(3);

        assertThat(index.currentIndex()).isPresent();
        assertThat(documents()).isEqualTo(3);
        assertThat(search("운동화를")).containsExactly(1L);
        assertThat(search("상품")).isEmpty();
    }

    @Test
    @DisplayName("색인 도중 실패하면 새 인덱스를 지우고 alias는 기존 인덱스를 계속 가리킨다")
    void keepsAliasWhenRebuildFails() throws IOException {
        product(1, "남성 러닝 운동화", 50000, "ON_SALE");
        indexer().rebuild();
        String before = index.currentIndex().orElseThrow();
        product(2, "겨울 니트", 30000, "ON_SALE");
        product(3, "여름 반팔 티셔츠", 10000, "ON_SALE");
        var calls = new AtomicInteger();
        var failing =
                new ProductSearchSource(named) {
                    @Override
                    public Map<Long, ProductSearchDocument> load(Collection<Long> ids) {
                        if (calls.incrementAndGet() == 2)
                            throw new IllegalStateException("DB 연결 끊김");
                        return super.load(ids);
                    }
                };
        clock.advance(Duration.ofMinutes(1));

        assertThatThrownBy(() -> indexer(failing).rebuild())
                .isInstanceOf(IllegalStateException.class);

        assertThat(index.currentIndex()).contains(before);
        assertThat(index.indexNames()).containsExactly(before);
        assertThat(search("운동화")).containsExactly(1L);
    }

    @Test
    @DisplayName("직전 인덱스 하나만 되돌리기용으로 남기고 더 오래된 인덱스는 지운다")
    void keepsOnlyPreviousIndex() throws IOException {
        product(1, "남성 러닝 운동화", 50000, "ON_SALE");
        FullIndexer indexer = indexer();
        indexer.rebuild();
        String first = index.currentIndex().orElseThrow();
        clock.advance(Duration.ofMinutes(1));
        indexer.rebuild();
        String second = index.currentIndex().orElseThrow();
        clock.advance(Duration.ofMinutes(1));
        indexer.rebuild();
        String third = index.currentIndex().orElseThrow();

        assertThat(index.indexNames()).containsExactlyInAnyOrder(second, third);
        assertThat(first).isNotIn(second, third);
    }

    @Test
    @DisplayName("alias가 없거나 지금 인덱스를 만든 뒤 예정 시각이 지났을 때만 다시 만든다")
    void decidesWhenToRebuild() throws IOException {
        product(1, "남성 러닝 운동화", 50000, "ON_SALE");
        FullIndexer indexer = indexer();
        assertThat(indexer.due()).isTrue();

        indexer.run();
        String first = index.currentIndex().orElseThrow();
        assertThat(indexer.due()).isFalse();

        // 한국 시간 다음 날 03:59, 아직 예정 시각 전이다.
        clock.set(Instant.parse("2026-11-25T18:59:00Z"));
        indexer.run();
        assertThat(index.currentIndex()).contains(first);

        // 한국 시간 다음 날 04:01
        clock.set(Instant.parse("2026-11-25T19:01:00Z"));
        assertThat(indexer.due()).isTrue();
        indexer.run();
        assertThat(index.currentIndex()).isPresent().get().isNotEqualTo(first);
        assertThat(indexer.due()).isFalse();
    }

    @Test
    @DisplayName("전체 색인 중에는 부분 색인이 돌지 않고, 그 사이 쌓인 작업은 alias를 옮긴 뒤 새 인덱스에 반영된다")
    void partialIndexingWaitsForFullIndexing() throws Exception {
        product(1, "기본 티셔츠", 10000, "ON_SALE");
        var loaded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking =
                new ProductSearchSource(named) {
                    @Override
                    public Map<Long, ProductSearchDocument> load(Collection<Long> ids) {
                        Map<Long, ProductSearchDocument> documents = super.load(ids);
                        loaded.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return documents;
                    }
                };
        var partial =
                new PartialIndexer(
                        named,
                        new ProductSearchSource(named),
                        index,
                        locks,
                        100,
                        Duration.ofMinutes(1));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var full = executor.submit(() -> indexer(blocking).run());
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();

            // 전체 색인이 상품을 읽은 뒤에 상품이 바뀐다.
            jdbc.update("UPDATE product SET name='줄무늬 티셔츠' WHERE id=1");
            jdbc.update("INSERT INTO index_task(product_id) VALUES(1)");
            partial.run();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM index_task", Long.class))
                    .isEqualTo(1);

            release.countDown();
            full.get(10, TimeUnit.SECONDS);
        }
        assertThat(search("줄무늬")).isEmpty();

        partial.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM index_task", Long.class)).isZero();
        assertThat(search("줄무늬")).containsExactly(1L);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
