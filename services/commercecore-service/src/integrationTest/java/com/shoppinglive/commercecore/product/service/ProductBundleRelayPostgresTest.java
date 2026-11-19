package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.dto.RegisterProductBundleRequest;
import com.shoppinglive.commercecore.product.dto.RegisterProductBundleRequest.InitialSku;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@EmbeddedKafka(
        partitions = 1,
        topics = {"commerce.product-info.v1", "commerce.sales-info.v1"})
@SpringBootTest(
        classes = ProductBundleRegistrationPostgresTest.Configuration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "shoppinglive.outbox.relay.enabled=true",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
        })
@DirtiesContext
@DisplayName("통합 등록의 PostgreSQL 커밋과 실제 Outbox 릴레이·EmbeddedKafka 전달")
class ProductBundleRelayPostgresTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) throws Exception {
        ProductBundleRegistrationPostgresTest.database(p);
    }

    @Autowired ProductBundleRegistrationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Value("${spring.embedded.kafka.brokers}")
    String broker;

    @Test
    @DisplayName("실패한 등록은 발행하지 않고 성공 커밋 뒤 상품과 SKU별 세 이벤트를 전달한다")
    void relaysOnlyCommittedBundle() throws Exception {
        var properties = new Properties();
        properties.put("bootstrap.servers", broker);
        properties.put("group.id", "bundle-" + UUID.randomUUID());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        var request =
                new RegisterProductBundleRequest(
                        "릴레이 상품",
                        "설명",
                        null,
                        List.of(new InitialSku("빨강", 100L, 2), new InitialSku("파랑", 200L, 0)));
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, 1), "png", bytes);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of("commerce.product-info.v1", "commerce.sales-info.v1"));
            jdbc.execute("ALTER TABLE sku ADD CONSTRAINT reject_bundle CHECK(price<>200)");
            assertThatThrownBy(
                            () ->
                                    service.register(
                                            "seller",
                                            "relay",
                                            request,
                                            bytes.toByteArray(),
                                            "image/png",
                                            "relay.png"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class))
                    .isZero();
            jdbc.execute("ALTER TABLE sku DROP CONSTRAINT reject_bundle");
            var result =
                    service.register(
                            "seller",
                            "relay",
                            request,
                            bytes.toByteArray(),
                            "image/png",
                            "relay.png");
            var received = new LinkedHashMap<String, JsonNode>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received.size() < 3 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    var envelope = mapper.readTree(record.value());
                    assertThat(envelope.path("payload").path("productId").asLong())
                            .isEqualTo(result.productId());
                    assertThat(record.key()).isEqualTo(envelope.path("aggregateId").asText());
                    received.put(envelope.path("eventId").asText(), envelope);
                }
            }
            assertThat(received).hasSize(3);
            assertThat(
                            received.values().stream()
                                    .filter(n -> n.path("payload").has("initialSkuCount"))
                                    .findFirst()
                                    .orElseThrow()
                                    .path("payload")
                                    .path("initialSkuCount")
                                    .asInt())
                    .isEqualTo(2);
            assertThat(
                            received.values().stream()
                                    .filter(n -> n.path("payload").has("status"))
                                    .map(n -> n.path("payload").path("status").asText())
                                    .toList())
                    .containsExactlyInAnyOrder("ON_SALE", "SOLD_OUT");
        }
    }
}
