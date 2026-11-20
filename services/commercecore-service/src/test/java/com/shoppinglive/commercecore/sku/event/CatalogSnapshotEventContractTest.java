package com.shoppinglive.commercecore.sku.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher;
import com.shoppinglive.common.outbox.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

class CatalogSnapshotEventContractTest {
    @Test
    @DisplayName("실제 상품·SKU·재고 생산자와 Outbox 직렬화기를 통해 CDC 봉투 세 종류를 생성한다")
    void exportsActualProducerOutboxText() throws Exception {
        var mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var repository = mock(OutboxMessageRepository.class);
        List<OutboxMessage> saved = new ArrayList<>();
        when(repository.save(any()))
                .thenAnswer(
                        call -> {
                            OutboxMessage m = call.getArgument(0);
                            saved.add(m);
                            return m;
                        });
        var outbox = new EventOutbox(repository, mapper, mock(ApplicationEventPublisher.class));
        var product = new Product("상품", "상품 설명", 10L, "fixture", "seller", null);
        Instant time = Instant.parse("2026-11-19T01:00:00Z");
        ReflectionTestUtils.setField(product, "id", 7L);
        ReflectionTestUtils.setField(product, "version", 0L);
        ReflectionTestUtils.setField(product, "createdAt", time);
        ReflectionTestUtils.setField(product, "updatedAt", time);

        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:cdc_fixture_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE product(id BIGINT,created_at TIMESTAMP WITH TIME ZONE,updated_at TIMESTAMP WITH TIME ZONE,deleted_at TIMESTAMP WITH TIME ZONE)");
        jdbc.update(
                "INSERT INTO product VALUES(7,?,?,NULL)",
                java.sql.Timestamp.from(time),
                java.sql.Timestamp.from(time));
        new ProductInfoEventPublisher(
                        outbox, id -> "/api/commercecore/v1/product-images/" + id, jdbc)
                .publish(product);
        jdbc.execute(
                "CREATE TABLE sku(id BIGINT,product_id BIGINT,metadata_version BIGINT,metadata_product_version BIGINT,price BIGINT,option_label VARCHAR(255),product_name_snapshot VARCHAR(255),sale_status VARCHAR(32),created_at TIMESTAMP WITH TIME ZONE,metadata_updated_at TIMESTAMP WITH TIME ZONE,deleted_at TIMESTAMP WITH TIME ZONE,inventory_version BIGINT,available INT,reserved INT,inventory_changed_at TIMESTAMP WITH TIME ZONE)");
        jdbc.update(
                "INSERT INTO sku VALUES(71,7,0,0,4000,'검정 / M','상품','ON_SALE',?,?,NULL,12,8,2,?)",
                java.sql.Timestamp.from(time),
                java.sql.Timestamp.from(time),
                java.sql.Timestamp.from(time));
        var publisher = new CatalogSnapshotPublisher(jdbc, outbox);
        publisher.metadata(71);
        publisher.inventory(71);
        Path samples = Path.of("build/contract-samples");
        Files.createDirectories(samples);
        Path fixture = Path.of("../../build/cdc-handoff");
        Files.createDirectories(fixture);
        assertThat(saved).hasSize(3);
        for (var message : saved) {
            var envelope = mapper.readTree(message.getPayload());
            assertThat(envelope.isObject()).isTrue();
            assertThat(envelope.path("aggregateId").asText()).isEqualTo(message.getMessageKey());
            assertThat(message.getTopic()).isEqualTo(CatalogSnapshotPublisher.TOPIC);
            Files.writeString(
                    fixture.resolve(message.getEventType() + ".json"), message.getPayload());
            mapper.writeValue(
                    samples.resolve(message.getEventType() + ".json").toFile(),
                    Map.of(
                            "contract",
                            "commerce-catalog-snapshots.yaml",
                            "message",
                            message.getEventType(),
                            "payload",
                            envelope));
        }
    }
}
