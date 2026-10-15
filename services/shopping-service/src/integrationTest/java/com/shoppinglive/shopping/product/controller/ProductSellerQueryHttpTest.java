package com.shoppinglive.shopping.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.shopping.product.entity.Product;
import com.shoppinglive.shopping.product.entity.ProductImage;
import com.shoppinglive.shopping.product.repository.ProductImageRepository;
import com.shoppinglive.shopping.product.repository.ProductRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import com.shoppinglive.shopping.storage.ImageFormat;
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

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:product_seller_query;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ProductSellerQueryHttpTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    private long imageId;

    @BeforeEach
    void image() {
        imageId =
                images.saveAndFlush(
                                new ProductImage(
                                        UUID.randomUUID() + ".png",
                                        ImageFormat.PNG,
                                        100,
                                        10,
                                        10,
                                        "fixture.png"))
                        .getId();
    }

    @AfterEach
    void clean() {
        products.deleteAll();
        images.deleteAll();
    }

    @DisplayName("내부 단건·일괄 상품 조회는 판매자 소유자를 반환한다")
    @Test
    void internalSingleAndBulkLookupExposeOwner() throws Exception {
        JsonNode result = register("owner-key");
        long id = result.path("productId").asLong();
        assertThat(products.findById(id).orElseThrow().getSellerId())
                .isEqualTo(JwtTestTokens.SELLER);
        mvc.perform(get("/v1/internal/products/{id}", id).header("X-Service-Token", COMMERCE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerId").value(JwtTestTokens.SELLER));
        mvc.perform(
                        get("/v1/internal/products")
                                .param("ids", Long.toString(id))
                                .header("X-Service-Token", COMMERCE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sellerId").value(JwtTestTokens.SELLER));
    }

    @DisplayName("본인 상품 목록은 타인·소유자 없는 상품을 제외하고 기존 조회는 유지한다")
    @Test
    void sellerListExcludesOtherAndUnassignedProductsAndRetainsLegacyLookup() throws Exception {
        register("owned-key");
        Product legacy =
                products.saveAndFlush(new Product("legacy", "legacy", imageId, "legacy-key"));
        products.saveAndFlush(
                new Product("other", "other", imageId, "other-key", JwtTestTokens.MEMBER_A));
        mvc.perform(get("/v1/seller/products").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("owned"));
        mvc.perform(
                        get("/v1/internal/products/{id}", legacy.getId())
                                .header("X-Service-Token", COMMERCE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerId").isEmpty());
        assertThat(products.findById(legacy.getId()).orElseThrow().getSellerId()).isNull();
    }

    @DisplayName("본인 상품 목록은 판매자 권한과 유효한 페이지를 요구한다")
    @Test
    void ownListRequiresSellerAndValidPagination() throws Exception {
        mvc.perform(get("/v1/seller/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/seller/products").header("Authorization", userBearer()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", adminBearer())
                                .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("offset 범위 초과를 거절하고 최대 지원 offset은 허용한다")
    @Test
    void rejectsOffsetOverflowAndAcceptsLargestSupportedOffset() throws Exception {
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", adminBearer())
                                .param("page", "2147483647")
                                .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", adminBearer())
                                .param("page", "2147483647")
                                .param("size", "1"))
                .andExpect(status().isOk());
    }

    private JsonNode register(String key) throws Exception {
        String result =
                mvc.perform(
                                post("/v1/admin/products")
                                        .header("Authorization", adminBearer())
                                        .header("X-Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "name",
                                                                "owned",
                                                                "description",
                                                                "owned",
                                                                "mainImageId",
                                                                imageId))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(result).path("data");
    }
}
