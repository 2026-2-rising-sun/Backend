package com.shoppinglive.shopping.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.shopping.image.domain.ImageFormat;
import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import com.shoppinglive.shopping.sales.infrastructure.InMemorySalesInfoClientStub;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:shopping_http_contracts;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "shopping.sales-client.mode=stub"
})
@AutoConfigureMockMvc
@DisplayName("Shopping 실제 HTTP 핵심 계약")
class ProductHttpContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired InMemorySalesInfoClientStub sales;

    private long imageId;

    @BeforeEach
    void prepareImageMetadata() {
        sales.clear();
        imageId = images.saveAndFlush(new ProductImage(UUID.randomUUID() + ".png",
            ImageFormat.PNG, 100, 10, 10, "fixture.png")).getId();
    }

    @AfterEach
    void cleanFixtures() {
        sales.clear();
        products.deleteAll();
        images.deleteAll();
    }

    @Test
    @DisplayName("상품 등록과 같은 멱등 키 재전송은 동일한 상품 하나를 반환한다")
    void registrationAndReplayReturnOneProduct() throws Exception {
        JsonNode first = register();
        JsonNode replay = register();

        assertThat(first.path("name").asText()).isEqualTo("상품");
        assertThat(first.path("mainImageId").asLong()).isEqualTo(imageId);
        assertThat(replay.path("productId").asLong()).isEqualTo(first.path("productId").asLong());
        assertThat(products.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("내부 벌크 조회는 판매정보 없는 상품도 조회하고 중복·미존재 ID를 제외한다")
    void internalLookupDoesNotDependOnSalesPublication() throws Exception {
        long id = register().path("productId").asLong();

        mvc.perform(get("/v1/internal/products")
                .param("ids", id + "," + id + "," + Long.MAX_VALUE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].id").value(id))
            .andExpect(jsonPath("$.data[0].name").value("상품"));
    }

    @Test
    @DisplayName("깨진 JSON은 400 INVALID_REQUEST이며 상품을 만들지 않는다")
    void malformedJsonReturnsBadRequestWithoutMutation() throws Exception {
        mvc.perform(post("/v1/admin/products")
                .header("X-Idempotency-Key", "malformed")
                .contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(products.count()).isZero();
    }

    @Test
    @DisplayName("없는 상품의 내부 단건 조회는 404 NOT_FOUND다")
    void missingProductReturnsNotFound() throws Exception {
        mvc.perform(get("/v1/internal/products/{id}", Long.MAX_VALUE))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("수정 후 이전 version을 재사용하면 409이며 먼저 저장한 값을 보존한다")
    void staleVersionCannotOverwriteUpdatedProduct() throws Exception {
        JsonNode registered = register();
        long id = registered.path("productId").asLong();
        long originalVersion = registered.path("version").asLong();

        String updated = mvc.perform(patch("/v1/admin/products/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "먼저 수정", "version", originalVersion))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("먼저 수정"))
            .andReturn().getResponse().getContentAsString();
        long updatedVersion = mapper.readTree(updated).path("data").path("version").asLong();
        assertThat(updatedVersion).isGreaterThan(originalVersion);

        mvc.perform(patch("/v1/admin/products/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "오래된 수정", "version", originalVersion))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        var saved = products.findById(id).orElseThrow();
        assertThat(saved.getName()).isEqualTo("먼저 수정");
        assertThat(saved.getVersion()).isEqualTo(updatedVersion);
    }

    @Test
    @DisplayName("판매정보 장애는 공개 상세503이며 내부 기본정보 조회는 계속200이다")
    void salesFailureDoesNotExposePurchaseDataOrBreakInternalLookup() throws Exception {
        long id = register().path("productId").asLong();
        sales.setUnavailable(true);

        mvc.perform(get("/v1/products/{id}", id))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SALES_INFO_UNAVAILABLE"))
            .andExpect(jsonPath("$.data", nullValue()));
        mvc.perform(get("/v1/internal/products/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(id));
    }

    private JsonNode register() throws Exception {
        String response = mvc.perform(post("/v1/admin/products")
                .header("X-Idempotency-Key", "product-http-contract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "상품", "description", "상품 설명",
                    "mainImageId", imageId))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }
}
