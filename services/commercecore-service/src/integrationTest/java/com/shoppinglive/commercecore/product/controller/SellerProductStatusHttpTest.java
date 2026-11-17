package com.shoppinglive.commercecore.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.entity.ProductImage;
import com.shoppinglive.commercecore.product.repository.ProductImageRepository;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.product.support.ProductSecuritySupport;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.storage.ImageFormat;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = com.shoppinglive.commercecore.product.support.ProductHttpTestApplication.class,
        properties =
                "spring.datasource.url=jdbc:h2:mem:catalog_query;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class SellerProductStatusHttpTest extends ProductSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired JdbcTemplate jdbc;
    private long imageId;

    @BeforeEach
    void prepare() {
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
        jdbc.update("DELETE FROM sku");
        jdbc.update("DELETE FROM sales_stock");
        jdbc.update("DELETE FROM sales_info");
        products.deleteAll();
        images.deleteAll();
    }

    private long product(String name, long price, SalesStatus status, String seller) {
        long id =
                products.saveAndFlush(
                                new Product(
                                        name, name, imageId, UUID.randomUUID().toString(), seller))
                        .getId();
        if (status != null) {
            com.shoppinglive.commercecore.sku.SkuFixtures.insert(
                    jdbc, id, price, status, status == SalesStatus.SOLD_OUT ? 0 : 1, 0);
        }
        return id;
    }

    @Autowired com.shoppinglive.commercecore.sales.repository.SalesJpaRepository sales;
    @Autowired com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository stocks;

    @Test
    @DisplayName("판매자 상태 필터는 다른 판매자 상품을 제외하고 전체 건수와 페이지를 계산한다")
    void sellerStatusPreservesOwnershipAndTotals() throws Exception {
        for (int i = 0; i < 5; i++)
            product("내 품절", 100, SalesStatus.SOLD_OUT, JwtTestTokens.SELLER);
        product("다른 판매자 품절", 100, SalesStatus.SOLD_OUT, UUID.randomUUID().toString());
        product("내 판매중", 100, SalesStatus.ON_SALE, JwtTestTokens.SELLER);
        JsonNode data =
                mapper.readTree(
                                mvc.perform(
                                                get("/v1/seller/products")
                                                        .header("Authorization", sellerBearer())
                                                        .param("status", "SOLD_OUT")
                                                        .param("size", "2")
                                                        .param("page", "2"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        assertThat(data.path("totalElements").asInt()).isEqualTo(5);
        assertThat(data.path("totalPages").asInt()).isEqualTo(3);
        assertThat(data.path("items").size()).isEqualTo(1);
        mvc.perform(get("/v1/seller/products").param("status", "SOLD_OUT"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", userBearer())
                                .param("status", "SOLD_OUT"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/v1/seller/products")
                                .header("Authorization", sellerBearer())
                                .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("판매정보 미등록 상태는 사본이 없는 본인 상품만 반환한다")
    void missingSalesFilterUsesOwnProductsOnly() throws Exception {
        long id = product("미등록", 0, null, JwtTestTokens.SELLER);
        product("다른 판매자", 0, null, UUID.randomUUID().toString());
        JsonNode data =
                mapper.readTree(
                                mvc.perform(
                                                get("/v1/seller/products")
                                                        .header("Authorization", sellerBearer())
                                                        .param("status", "NOT_REGISTERED"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("items").get(0).path("productId").asLong()).isEqualTo(id);
        assertThat(data.path("items").get(0).path("price").isNull()).isTrue();
    }
}
