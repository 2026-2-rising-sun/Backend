package com.shoppinglive.catalog.product.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.catalog.client.commercecore.SalesInfo;
import com.shoppinglive.catalog.client.commercecore.SalesStatus;
import com.shoppinglive.catalog.product.repository.ProductRepository;
import com.shoppinglive.catalog.sales.repository.ProductSalesRepository;
import com.shoppinglive.catalog.security.CatalogSecuritySupport;
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
        properties = {
            // 판매정보 장애 계약은 되돌리기 스위치인 http 경로에서 유지한다.
            "catalog.sales.source=http",
            "spring.datasource.url=jdbc:h2:mem:shopping_http_contracts;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        })
@AutoConfigureMockMvc
@DisplayName("Shopping 실제 HTTP 핵심 계약")
class ProductHttpContractTest extends CatalogSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductRepository products;
    @Autowired ProductSalesRepository sales;
    @Autowired JdbcTemplate jdbc;

    private long imageId;

    @BeforeEach
    void prepareImageMetadata() {
        imageId = 100L;
    }

    @AfterEach
    void cleanFixtures() {
        products.deleteAll();
    }

    @Test
    @DisplayName("판매정보 HTTP 장애 시 공개 상세는 구매 데이터를 노출하지 않는다")
    void salesFailureDoesNotExposePurchaseDataOrBreakInternalLookup() throws Exception {
        long id = register().path("productId").asLong();
        mvc.perform(get("/v1/products/{id}", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SALES_INFO_UNAVAILABLE"))
                .andExpect(jsonPath("$.data", nullValue()));
    }

    @Test
    @DisplayName("Commerce에 연결할 수 없어도 공개 목록은 판매정보 사본으로 200을 반환한다")
    void publicListIsServedFromReplicaWhileCommerceIsUnreachable() throws Exception {
        long id = register().path("productId").asLong();
        sales.apply(new SalesInfo(id, id, 1_000L, SalesStatus.ON_SALE, 3), 1);
        try {
            mvc.perform(get("/v1/products"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].productId").value(id))
                    .andExpect(jsonPath("$.data.items[0].price").value(1_000L))
                    .andExpect(jsonPath("$.data.items[0].available").value(3));
        } finally {
            jdbc.update("DELETE FROM product_sales");
        }
    }

    private JsonNode register() {
        var product =
                products.saveAndFlush(
                        com.shoppinglive.catalog.product.support.ProductReplicaFixtures.product(
                                "상품", "상품 설명", imageId, null));
        return mapper.createObjectNode().put("productId", product.getId());
    }
}
