package com.shoppinglive.commercecore.product.event;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.commercecore.product.entity.ProductCategory;
import com.shoppinglive.common.events.EventEnvelope;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;

class ProductInfoEventContractTest {
    @Test
    @DisplayName("실제 상품 snapshot DTO의 version 0·원본 시각·Core 이미지 URL을 직렬화 표본으로 내보낸다")
    void exportsActualProductSnapshotForContractValidation() throws Exception {
        var mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var created = Instant.parse("2026-11-08T00:00:00Z");
        var payload =
                new ProductInfoChangedEvent(
                        7,
                        "상품",
                        "상품 설명",
                        ProductCategory.FASHION,
                        10,
                        "/api/commercecore/v1/product-images/10",
                        "seller",
                        0,
                        created,
                        created);
        var envelope =
                EventEnvelope.create(
                        ProductInfoEventPublisher.EVENT_TYPE,
                        1,
                        "commercecore-service",
                        "7",
                        created.plusSeconds(1),
                        payload);
        var json = mapper.readTree(mapper.writeValueAsBytes(envelope));
        assertThat(json.at("/payload/version").asLong()).isZero();
        assertThat(json.at("/payload/mainImageUrl").asText())
                .isEqualTo("/api/commercecore/v1/product-images/10");
        assertThat(json.path("occurredAt").asText())
                .isNotEqualTo(json.at("/payload/updatedAt").asText());
        assertThat(ProductInfoEventPublisher.TOPIC).isEqualTo("commerce.product-info.v1");
        var dir = Path.of("build/contract-samples");
        Files.createDirectories(dir);
        mapper.writeValue(
                dir.resolve("PRODUCT_INFO_CHANGED.json").toFile(),
                Map.of(
                        "contract",
                        "commerce-product-info.yaml",
                        "message",
                        "PRODUCT_INFO_CHANGED",
                        "payload",
                        envelope));
    }
}
