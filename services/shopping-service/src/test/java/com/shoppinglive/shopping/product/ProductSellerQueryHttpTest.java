package com.shoppinglive.shopping.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.shopping.image.domain.ImageFormat;
import com.shoppinglive.shopping.image.domain.ProductImage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:product_seller_query;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ProductSellerQueryHttpTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    private long imageId;

    @BeforeEach
    void image() {
        imageId = images.saveAndFlush(new ProductImage(UUID.randomUUID() + ".png", ImageFormat.PNG,
            100, 10, 10, "fixture.png")).getId();
    }

    @AfterEach
    void clean() {
        products.deleteAll();
        images.deleteAll();
    }

    @Test
    void internalSingleAndBulkLookupExposeOwner() throws Exception {
        JsonNode result = register("owner-key");
        long id = result.path("productId").asLong();
        assertThat(products.findById(id).orElseThrow().getSellerId()).isEqualTo(JwtTestTokens.SELLER);
        mvc.perform(get("/v1/internal/products/{id}", id).header("X-Service-Token", COMMERCE_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sellerId").value(JwtTestTokens.SELLER));
        mvc.perform(get("/v1/internal/products").param("ids", Long.toString(id)).header("X-Service-Token", COMMERCE_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].sellerId").value(JwtTestTokens.SELLER));
    }

    @Test
    void sellerListExcludesOtherAndUnassignedProductsAndRetainsLegacyLookup() throws Exception {
        register("owned-key");
        Product legacy = products.saveAndFlush(new Product("legacy", "legacy", imageId, "legacy-key"));
        products.saveAndFlush(new Product("other", "other", imageId, "other-key", JwtTestTokens.MEMBER_A));
        mvc.perform(get("/v1/seller/products").header("Authorization", adminBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].name").value("owned"));
        mvc.perform(get("/v1/internal/products/{id}", legacy.getId()).header("X-Service-Token", COMMERCE_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sellerId").isEmpty());
        assertThat(products.findById(legacy.getId()).orElseThrow().getSellerId()).isNull();
    }

    @Test
    void ownListRequiresSellerAndValidPagination() throws Exception {
        mvc.perform(get("/v1/seller/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/seller/products").header("Authorization", userBearer()))
            .andExpect(status().isForbidden());
        mvc.perform(get("/v1/seller/products").header("Authorization", adminBearer()).param("size", "101"))
            .andExpect(status().isBadRequest());
    }

    private JsonNode register(String key) throws Exception {
        String result = mvc.perform(post("/v1/admin/products").header("Authorization", adminBearer())
                .header("X-Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "owned", "description", "owned", "mainImageId", imageId))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(result).path("data");
    }
}
