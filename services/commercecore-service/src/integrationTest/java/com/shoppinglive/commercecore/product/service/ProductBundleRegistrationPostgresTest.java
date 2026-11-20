package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.product.dto.RegisterProductBundleRequest.InitialSku;
import com.shoppinglive.commercecore.storage.*;
import com.shoppinglive.common.core.BusinessException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(
        classes = ProductBundleRegistrationPostgresTest.Configuration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "shoppinglive.outbox.relay.enabled=false"
        })
@DisplayName("통합 등록의 PostgreSQL 원자성·멱등성과 실제 로컬 파일 복구")
class ProductBundleRegistrationPostgresTest {
    @org.springframework.boot.test.context.TestComponent
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Import({
        ProductPersistenceTest.Configuration.class,
        ProductBundleRegistrationService.class,
        ImageValidator.class,
        LocalImageStorage.class
    })
    static class Configuration {}

    static final Path DIRECTORY;

    static {
        try {
            DIRECTORY = Files.createTempDirectory("registration372-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) throws Exception {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        String user = System.getenv("COMMERCECORE_TEST_POSTGRES_USER");
        String password = System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD");
        String schema = "bundle_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(url, user, password);
                var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        p.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
        p.add("spring.datasource.username", () -> user);
        p.add("spring.datasource.password", () -> password);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.schemas", () -> schema);
        p.add("commercecore.image.storage.dir", () -> DIRECTORY.toString());
    }

    @Autowired ProductBundleRegistrationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean ImageStorage storage;

    byte[] png() throws Exception {
        var image =
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    RegisterProductBundleRequest request() {
        return new RegisterProductBundleRequest(
                "상품",
                "설명",
                null,
                List.of(new InitialSku("빨강", 100L, 3), new InitialSku("파랑", 200L, 0)));
    }

    ProductBundleResponse register(String key) throws Exception {
        return service.register("seller", key, request(), png(), "image/png", "test.png");
    }

    @BeforeEach
    void clear() throws Exception {
        reset(storage);
        jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT IF EXISTS reject_outbox");
        jdbc.execute("ALTER TABLE sku DROP CONSTRAINT IF EXISTS reject_second_sku");
        for (String table :
                List.of("event_outbox", "sku", "product", "product_image", "product_registration"))
            jdbc.update("DELETE FROM " + table);
        try (var paths = Files.list(DIRECTORY)) {
            for (var file : paths.toList()) Files.delete(file);
        }
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    void noBusinessRows() {
        for (String table : List.of("product", "product_image", "sku", "event_outbox"))
            assertThat(count(table)).as(table).isZero();
    }

    @Test
    @DisplayName("상품과 모든 판매중 SKU 및 Outbox가 함께 커밋되고 동일 결과를 재응답한다")
    void commitsAndReplays() throws Exception {
        var response = register("same");
        assertThat(response.saleEnabled()).isTrue();
        assertThat(response.skus()).hasSize(2);
        assertThat(response.skus().get(1).status().name()).isEqualTo("SOLD_OUT");
        assertThat(jdbc.queryForList("SELECT sale_status FROM sku", String.class))
                .containsOnly("ON_SALE");
        assertThat(jdbc.queryForList("SELECT reserved FROM sku", Integer.class)).containsOnly(0);
        com.shoppinglive.commercecore.product.support.ProductBundleOutboxAssertions
                .assertRegisteredBundle(
                        jdbc,
                        mapper,
                        response.productId(),
                        response.skus().stream()
                                .map(ProductBundleResponse.RegisteredSku::skuId)
                                .toList());
        assertThat(jdbc.queryForObject("SELECT initial_sku_count FROM product", Integer.class))
                .isEqualTo(2);
        assertThat(register("same")).isEqualTo(response);
        com.shoppinglive.commercecore.product.support.ProductBundleOutboxAssertions
                .assertRegisteredBundle(
                        jdbc,
                        mapper,
                        response.productId(),
                        response.skus().stream()
                                .map(ProductBundleResponse.RegisteredSku::skuId)
                                .toList());
        assertThat(count("product")).isEqualTo(1);
        try (var paths = Files.list(DIRECTORY)) {
            assertThat(paths.count()).isEqualTo(1);
        }
        var events = jdbc.queryForList("SELECT payload FROM event_outbox", String.class);
        assertThat(
                        events.stream()
                                .map(
                                        s -> {
                                            try {
                                                return mapper.readTree(s);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
                                .filter(n -> n.path("payload").has("initialSkuCount"))
                                .findFirst()
                                .orElseThrow()
                                .path("payload")
                                .path("initialSkuCount")
                                .asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("두 번째 SKU 저장 실패는 이미지 메타데이터·상품·첫 SKU·Outbox를 모두 롤백하고 파일을 삭제한다")
    void rollsBackSecondSkuFailure() {
        jdbc.execute("ALTER TABLE sku ADD CONSTRAINT reject_second_sku CHECK(price<>200)");
        assertThatThrownBy(() -> register("failure")).isInstanceOf(RuntimeException.class);
        noBusinessRows();
        assertThat(
                        storage.exists(
                                jdbc.queryForObject(
                                        "SELECT storage_key FROM product_registration",
                                        String.class)))
                .isFalse();
    }

    @Test
    @DisplayName("상품·SKU CDC와 기존 판매 알림의 각 Outbox 실패는 완료 응답·전체 등록·파일을 롤백한다")
    void rollsBackOutboxFailures() throws Exception {
        var failures =
                List.of(
                        Map.entry("ProductSnapshotChanged", "commerce.catalog.snapshots.v1"),
                        Map.entry("SkuSnapshotChanged", "commerce.catalog.snapshots.v1"),
                        Map.entry("SalesRegistered", "commerce.sales-info.v1"));
        for (var failure : failures) {
            jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT IF EXISTS reject_outbox");
            jdbc.execute(
                    "ALTER TABLE event_outbox ADD CONSTRAINT reject_outbox CHECK(NOT(topic='"
                            + failure.getValue()
                            + "' AND event_type='"
                            + failure.getKey()
                            + "'))");
            assertThatThrownBy(() -> register(failure.getKey()))
                    .as(failure.getKey())
                    .isInstanceOf(RuntimeException.class);
            noBusinessRows();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM product_registration WHERE response_json IS NOT NULL",
                                    Long.class))
                    .isZero();
            String key =
                    jdbc.queryForObject(
                            "SELECT storage_key FROM product_registration WHERE seller_id='seller' AND request_key=?",
                            String.class,
                            failure.getKey());
            assertThat(storage.exists(key)).as(failure.getKey()).isFalse();
            try (var files = Files.list(DIRECTORY)) {
                assertThat(files.count()).isZero();
            }
        }
        // 별도 커밋한 복구 위치는 남지만 완료 멱등 응답은 없다. 같은 요청을 다시 완료할 수 있다.
        jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT reject_outbox");
        var replay = register("SkuSnapshotChanged");
        assertThat(register("SkuSnapshotChanged")).isEqualTo(replay);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM product_registration WHERE response_json IS NOT NULL",
                                Long.class))
                .isEqualTo(1);
        com.shoppinglive.commercecore.product.support.ProductBundleOutboxAssertions
                .assertRegisteredBundle(
                        jdbc,
                        mapper,
                        replay.productId(),
                        replay.skus().stream()
                                .map(ProductBundleResponse.RegisteredSku::skuId)
                                .toList());
    }

    @Test
    @DisplayName("파일 저장 실패는 상품·이미지 메타데이터·SKU를 만들지 않는다")
    void rejectsStorageFailure() {
        doThrow(new ImageStorageException("파일 저장 실패 주입", null))
                .when(storage)
                .store(anyString(), any(), anyLong(), anyString());
        assertThatThrownBy(() -> register("storage")).isInstanceOf(ImageStorageException.class);
        noBusinessRows();
    }

    @Test
    @DisplayName("삭제 실패로 남은 파일은 재시도에서 재사용하고 완료 파일은 정리하지 않는다")
    void reusesFileAfterCleanupFailure() throws Exception {
        doThrow(new ImageStorageException("삭제 실패 주입", null)).when(storage).delete(anyString());
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT reject_outbox CHECK(false)");
        assertThatThrownBy(() -> register("retry")).isInstanceOf(RuntimeException.class);
        noBusinessRows();
        String key =
                jdbc.queryForObject("SELECT storage_key FROM product_registration", String.class);
        assertThat(storage.exists(key)).isTrue();
        reset(storage);
        jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT IF EXISTS reject_outbox");
        register("retry");
        jdbc.update(
                "UPDATE product_registration SET updated_at=CURRENT_TIMESTAMP-INTERVAL '2 hours'");
        service.cleanupAbandoned();
        assertThat(storage.exists(key)).isTrue();
        verify(storage, never()).store(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("프로세스 중단 기록의 미사용 파일은 정리하고 같은 요청은 다시 완료할 수 있다")
    void cleansAbandonedAndRetries() throws Exception {
        doThrow(new ImageStorageException("삭제 실패", null)).when(storage).delete(anyString());
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT reject_outbox CHECK(false)");
        assertThatThrownBy(() -> register("abandoned")).isInstanceOf(RuntimeException.class);
        reset(storage);
        jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT IF EXISTS reject_outbox");
        String key =
                jdbc.queryForObject("SELECT storage_key FROM product_registration", String.class);
        jdbc.update(
                "UPDATE product_registration SET updated_at=CURRENT_TIMESTAMP-INTERVAL '2 hours'");
        service.cleanupAbandoned();
        assertThat(storage.exists(key)).isFalse();
        Path interrupted =
                DIRECTORY.resolve(
                        ".upload-"
                                + Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(
                                                key.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8))
                                + ".interrupted.tmp");
        Files.write(interrupted, new byte[] {1});
        assertThat(register("abandoned").skus()).hasSize(2);
        assertThat(Files.exists(interrupted)).isFalse();
    }

    @Test
    @DisplayName("오래된 요청을 재개하는 동안 정리 작업은 활성 행을 건너뛴다")
    void skipsActiveRegistration() throws Exception {
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT reject_outbox CHECK(false)");
        assertThatThrownBy(() -> register("active")).isInstanceOf(RuntimeException.class);
        jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT reject_outbox");
        jdbc.update(
                "UPDATE product_registration SET updated_at=CURRENT_TIMESTAMP-INTERVAL '2 hours'");
        clearInvocations(storage);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("저장 재개 신호 누락");
                            return call.callRealMethod();
                        })
                .when(storage)
                .store(anyString(), any(), anyLong(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> register("active"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                service.cleanupAbandoned();
                verify(storage, never()).delete(anyString());
            } finally {
                release.countDown();
            }
            assertThat(request.get(10, TimeUnit.SECONDS).skus()).hasSize(2);
        }
    }

    @Test
    @DisplayName("파일 정리는 메타데이터가 연결된 이미지를 보존한다")
    void preservesReferencedImage() throws Exception {
        doThrow(new ImageStorageException("삭제 실패", null)).when(storage).delete(anyString());
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT reject_outbox CHECK(false)");
        assertThatThrownBy(() -> register("protected")).isInstanceOf(RuntimeException.class);
        reset(storage);
        String key =
                jdbc.queryForObject("SELECT storage_key FROM product_registration", String.class);
        jdbc.update(
                """
                INSERT INTO product_image(storage_key,content_type,size_bytes,width,height,created_at,updated_at)
                VALUES(?,'image/png',10,2,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                key);
        jdbc.update(
                "UPDATE product_registration SET updated_at=CURRENT_TIMESTAMP-INTERVAL '2 hours'");
        service.cleanupAbandoned();
        assertThat(storage.exists(key)).isTrue();
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("같은 키의 다른 내용은 거절하고 판매자별 키는 독립적이다")
    void checksFingerprintAndSellerScope() throws Exception {
        var result = register("key");
        var changed = new RegisterProductBundleRequest("다른 상품", "설명", null, request().skus());
        assertThatThrownBy(
                        () ->
                                service.register(
                                        "seller", "key", changed, png(), "image/png", "test.png"))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        service.register("other", "key", request(), png(), "image/png", "test.png")
                                .productId())
                .isNotEqualTo(result.productId());
    }

    @Test
    @DisplayName("동시 재전송은 상품·SKU·파일을 한 번만 생성한다")
    void concurrentReplay() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<ProductBundleResponse> call =
                    () -> {
                        start.await();
                        return register("concurrent");
                    };
            var a = executor.submit(call);
            var b = executor.submit(call);
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("product")).isEqualTo(1);
        assertThat(count("sku")).isEqualTo(2);
        try (var paths = Files.list(DIRECTORY)) {
            assertThat(paths.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("입력 오류는 복구 기록과 파일 저장 전에 거절한다")
    void validatesBeforeWriting() throws Exception {
        for (var list :
                List.of(
                        List.<InitialSku>of(),
                        List.of(new InitialSku(null, 0L, 0)),
                        List.of(new InitialSku("같음", 1L, 0), new InitialSku(" 같음 ", 2L, 1)))) {
            assertThatThrownBy(
                            () ->
                                    service.register(
                                            "seller",
                                            "invalid",
                                            new RegisterProductBundleRequest(
                                                    "상품", "설명", null, list),
                                            png(),
                                            "image/png",
                                            "test.png"))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(count("product_registration")).isZero();
        verify(storage, never()).store(anyString(), any(), anyLong(), anyString());
    }
}
