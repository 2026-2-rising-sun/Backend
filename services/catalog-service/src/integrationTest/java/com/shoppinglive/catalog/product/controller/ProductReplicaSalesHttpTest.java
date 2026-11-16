package com.shoppinglive.catalog.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.catalog.client.commercecore.HttpSalesInfoClient;
import com.shoppinglive.catalog.client.commercecore.SalesInfo;
import com.shoppinglive.catalog.client.commercecore.SalesStatus;
import com.shoppinglive.catalog.product.repository.ProductRepository;
import com.shoppinglive.catalog.sales.repository.ProductSalesRepository;
import com.shoppinglive.catalog.security.CatalogSecuritySupport;
import com.shoppinglive.common.security.test.JwtTestTokens;
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
 * 기본 설정(사본)에서 네 조회가 Commerce 없이 사본만으로 응답하는지 확인한다. HTTP 클라이언트는 mock 이라 호출되면 빈 결과를 돌려주고, 어떤 호출이든
 * {@code verifyNoInteractions} 로 실패한다.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:product_replica_sales_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                    + "DATABASE_TO_LOWER=TRUE"
        })
@AutoConfigureMockMvc
@DisplayName("기본 설정(replica)의 상품 판매정보 조회")
class ProductReplicaSalesHttpTest extends CatalogSecuritySupport {

    private static final long PRICE = 1_500L;

    @Autowired MockMvc mvc;
    @Autowired ProductRepository products;
    @Autowired ProductSalesRepository replica;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HttpSalesInfoClient commerce;

    private long imageId;

    @BeforeEach
    void prepareImage() {
        imageId = 100L;
    }

    @AfterEach
    void cleanFixtures() {
        verifyNoInteractions(commerce);
        jdbc.update("DELETE FROM product_sales");
        products.deleteAll();
    }

    @Test
    @DisplayName("판매 중 상품은 목록·상세·구매 사전확인 모두 사본의 가격과 재고로 응답한다")
    void saleFromReplicaIsServedByAllFourReads() throws Exception {
        long id = product("on-sale");
        sales(id, SalesStatus.ON_SALE, 4);

        mvc.perform(get("/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].productId").value(id))
                .andExpect(jsonPath("$.data.items[0].price").value(PRICE))
                .andExpect(jsonPath("$.data.items[0].available").value(4))
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
    }

    @Test
    @DisplayName("비공개 행과 사본에 없는 상품은 공개 조회에서 숨는다")
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
    }

    @Test
    @DisplayName("기본 목록은 재고 0을 반환하고 판매중·품절 상태와 구매 불가를 유지한다")
    void zeroStockPreservesSalesStatusAndPreventsPurchase() throws Exception {
        for (SalesStatus salesStatus :
                new SalesStatus[] {SalesStatus.ON_SALE, SalesStatus.SOLD_OUT}) {
            long id = product(salesStatus.name());
            sales(id, salesStatus, 0);
            mvc.perform(get("/v1/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].productId").value(id))
                    .andExpect(jsonPath("$.data.items[0].available").value(0))
                    .andExpect(jsonPath("$.data.items[0].salesStatus").value(salesStatus.name()))
                    .andExpect(jsonPath("$.data.items[0].purchasable").value(false));
        }
    }

    private long product(String name) {
        return products.saveAndFlush(
                        com.shoppinglive.catalog.product.support.ProductReplicaFixtures.product(
                                name, name, imageId, JwtTestTokens.SELLER))
                .getId();
    }

    @Test
    @DisplayName("상품 전체 중지는 공개 상태·재고를 보존하며 구매만 막고 삭제는 모든 공개 조회에서 제외한다")
    void wholeControlDoesNotPretendToBeStockoutAndDeletionStaysHidden() throws Exception {
        long id = product("paused");
        sales(id, SalesStatus.ON_SALE, 4);
        jdbc.update("UPDATE product SET sale_enabled=FALSE WHERE id=?", id);
        mvc.perform(get("/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].salesStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data.items[0].available").value(4))
                .andExpect(jsonPath("$.data.items[0].purchasable").value(false));
        mvc.perform(get("/v1/products/{id}/purchase-check", id).param("quantity", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderable").value(false))
                .andExpect(jsonPath("$.data.reason").value("SALE_SUSPENDED"))
                .andExpect(jsonPath("$.data.maxQuantity").value(0));
        jdbc.update("UPDATE product SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", id);
        mvc.perform(get("/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", empty()));
        mvc.perform(get("/v1/products/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/products/{id}/purchase-check", id).param("quantity", "1"))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available FROM product_sales WHERE product_id=?",
                                Integer.class,
                                id))
                .isEqualTo(4);
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
