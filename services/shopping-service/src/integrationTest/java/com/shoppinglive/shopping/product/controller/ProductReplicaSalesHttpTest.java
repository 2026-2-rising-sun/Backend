package com.shoppinglive.shopping.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.shopping.client.commerce.HttpSalesInfoClient;
import com.shoppinglive.shopping.client.commerce.SalesInfo;
import com.shoppinglive.shopping.client.commerce.SalesStatus;
import com.shoppinglive.shopping.product.entity.Product;
import com.shoppinglive.shopping.product.entity.ProductImage;
import com.shoppinglive.shopping.product.repository.ProductImageRepository;
import com.shoppinglive.shopping.product.repository.ProductRepository;
import com.shoppinglive.shopping.sales.repository.ProductSalesRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import com.shoppinglive.shopping.storage.ImageFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code shopping.sales.source=replica} 에서 네 조회가 Commerce 없이 사본만으로 응답하는지 확인한다. HTTP 클라이언트는 mock 이라
 * 호출되면 빈 결과를 돌려주고, 어떤 호출이든 {@code verifyNoInteractions} 로 실패한다.
 */
@SpringBootTest(
        properties = {
            "shopping.sales.source=replica",
            "spring.datasource.url=jdbc:h2:mem:product_replica_sales_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                    + "DATABASE_TO_LOWER=TRUE"
        })
@AutoConfigureMockMvc
@DisplayName("replica 모드의 상품 판매정보 조회")
class ProductReplicaSalesHttpTest extends ShoppingSecuritySupport {

    private static final long PRICE = 1_500L;

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired ProductImageRepository images;
    @Autowired ProductSalesRepository replica;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HttpSalesInfoClient commerce;

    private long imageId;

    @BeforeEach
    void prepareImage() {
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
    void cleanFixtures() {
        verifyNoInteractions(commerce);
        jdbc.update("DELETE FROM product_sales");
        products.deleteAll();
        images.deleteAll();
    }

    @Test
    @DisplayName("판매 중 상품은 목록·상세·구매 사전확인·판매자 조회 모두 사본의 가격과 재고로 응답한다")
    void saleFromReplicaIsServedByAllFourReads() throws Exception {
        long id = product("on-sale");
        sales(id, SalesStatus.ON_SALE, 4);

        mvc.perform(get("/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productId").value(id))
                .andExpect(jsonPath("$.data.items[0].price").value(PRICE))
                .andExpect(jsonPath("$.data.items[0].salesStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data.items[0].purchasable").value(true));
        mvc.perform(get("/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(PRICE))
                .andExpect(jsonPath("$.data.available").value(4))
                .andExpect(jsonPath("$.data.purchasable").value(true));
        mvc.perform(get("/v1/products/{id}/purchase-check", id).param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderable").value(true))
                .andExpect(jsonPath("$.data.unitPrice").value(PRICE));
        mvc.perform(get("/v1/admin/products").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].salesStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data.items[0].price").value(PRICE));
        mvc.perform(get("/v1/admin/products/{id}", id).header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data.available").value(4));
    }

    @Test
    @DisplayName("비공개 행과 사본에 없는 상품은 공개 조회에서 숨고, 판매자 조회에는 각각 PRIVATE·NOT_REGISTERED로 보인다")
    void privateAndMissingRowsStayHiddenFromPublicReads() throws Exception {
        long hidden = product("hidden");
        long unregistered = product("unregistered");
        sales(hidden, SalesStatus.PRIVATE, 4);

        mvc.perform(get("/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", empty()));
        for (long id : new long[] {hidden, unregistered}) {
            mvc.perform(get("/v1/products/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            mvc.perform(get("/v1/products/{id}/purchase-check", id).param("quantity", "1"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/v1/admin/products").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.items[?(@.name=='hidden')].salesStatus",
                                contains("PRIVATE")))
                .andExpect(
                        jsonPath(
                                "$.data.items[?(@.name=='unregistered')].salesStatus",
                                contains("NOT_REGISTERED")))
                .andExpect(
                        jsonPath(
                                "$.data.items[?(@.name=='unregistered')].price",
                                contains(nullValue())));
        mvc.perform(
                        get("/v1/admin/products/{id}", unregistered)
                                .header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salesStatus").value("NOT_REGISTERED"));
    }

    private long product(String name) {
        return products.saveAndFlush(new Product(name, name, imageId, name + "-key")).getId();
    }

    private void sales(long productId, SalesStatus status, int available) {
        // 이벤트 경로와 같은 저장 규칙으로 사본 행을 넣는다 (version 1).
        assertThat(
                        replica.apply(
                                new SalesInfo(productId, productId * 10, PRICE, status, available),
                                1L))
                .isTrue();
    }
}
