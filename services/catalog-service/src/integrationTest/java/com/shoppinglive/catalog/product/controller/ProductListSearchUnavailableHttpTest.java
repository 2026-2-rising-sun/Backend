package com.shoppinglive.catalog.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.catalog.security.CatalogSecuritySupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** ES 에 연결할 수 없는 상태. 연결을 거절하는 루프백 주소를 ES 로 둔다. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:catalog_search_down;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "spring.elasticsearch.uris=http://127.0.0.1:1"
        })
@AutoConfigureMockMvc
class ProductListSearchUnavailableHttpTest extends CatalogSecuritySupport {
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("ES에 연결할 수 없으면 목록은 DB로 대신하지 않고 503 SERVICE_UNAVAILABLE이다")
    void listIsUnavailableWithoutSearch() throws Exception {
        mvc.perform(get("/v1/products").param("q", "니트").param("sort", "PRICE_ASC"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("입력 오류는 ES에 묻기 전에 400으로 응답한다")
    void invalidInputIsRejectedBeforeSearch() throws Exception {
        mvc.perform(get("/v1/products").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("status", "PRIVATE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("minPrice", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("maxPrice", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("minPrice", "3000").param("maxPrice", "2000"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("minPrice", "만원")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/products").param("cursor", "broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
