package com.shoppinglive.catalog.product.event;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ProductInfoEventMessageTest {
    private ObjectNode event() {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("eventId", UUID.randomUUID().toString())
                .put("eventType", "PRODUCT_INFO_CHANGED")
                .put("schemaVersion", 1)
                .put("source", "commercecore-service")
                .put("aggregateId", "7")
                .put("occurredAt", "2026-11-08T00:00:00Z");
        root.putObject("payload")
                .put("productId", 7)
                .put("name", "상품")
                .put("description", "설명")
                .putNull("category")
                .put("mainImageId", 10)
                .put("mainImageUrl", "/api/commercecore/v1/product-images/10")
                .putNull("sellerId")
                .put("version", 0)
                .put("createdAt", "2026-11-08T00:00:00Z")
                .put("updatedAt", "2026-11-08T00:00:00Z");
        return root;
    }

    @Test
    @DisplayName("최초 버전 0과 미분류·소유자 없는 snapshot을 허용한다")
    void acceptsInitialSnapshot() {
        var result = ProductInfoEventMessage.parse("7", event().toString());
        assertThat(result.version()).isZero();
        assertThat(result.category()).isNull();
        assertThat(result.sellerId()).isNull();
        assertThat(result.saleEnabled()).isTrue();
        assertThat(result.deletedAt()).isNull();
    }

    @Test
    @DisplayName("신규 판매 준비·삭제 snapshot을 구분하고 잘못된 제어 타입과 삭제 후 판매허용은 거절한다")
    void validatesSaleControlAndDeletion() {
        var node = event();
        var p = (ObjectNode) node.path("payload");
        p.put("saleEnabled", false).putNull("deletedAt");
        assertThat(ProductInfoEventMessage.parse("7", node.toString()).saleEnabled()).isFalse();
        p.put("deletedAt", "2026-11-13T00:00:00Z");
        assertThat(ProductInfoEventMessage.parse("7", node.toString()).deletedAt()).isNotNull();
        p.put("saleEnabled", true);
        reject(node);
        p.put("saleEnabled", "false");
        reject(node);
        p.putNull("saleEnabled");
        reject(node);
        p.put("saleEnabled", false).put("deletedAt", "invalid");
        reject(node);
    }

    @Test
    @DisplayName("봉투·키·스키마·정수 타입이 계약과 다르면 사본 쓰기 전에 거부한다")
    void rejectsInvalidEnvelopeAndPayload() {
        for (String field :
                new String[] {"eventId", "eventType", "source", "aggregateId", "occurredAt"}) {
            var node = event();
            node.put(field, "invalid");
            reject(node);
        }
        var node = event();
        node.put("schemaVersion", 2);
        reject(node);
        node = event();
        ((ObjectNode) node.path("payload")).put("version", -1);
        reject(node);
        node = event();
        ((ObjectNode) node.path("payload")).put("version", "0");
        reject(node);
        node = event();
        ((ObjectNode) node.path("payload")).put("category", "WRONG");
        reject(node);
        String valid = event().toString();
        assertThatThrownBy(() -> ProductInfoEventMessage.parse("8", valid))
                .isInstanceOf(ProductInfoEventMessage.InvalidMessageException.class);
    }

    private void reject(ObjectNode node) {
        assertThatThrownBy(() -> ProductInfoEventMessage.parse("7", node.toString()))
                .isInstanceOf(ProductInfoEventMessage.InvalidMessageException.class);
    }
}
