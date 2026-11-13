package com.shoppinglive.commercecore.cart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Sql("classpath:cart/sku-fixture.sql")
class CartApiIntegrationTest extends CommerceCoreSecurityTestSupport {
    private static final String OWNER = "44444444-4444-4444-8444-444444444444";
    private static final String OTHER = "55555555-5555-4555-8555-555555555555";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CartItemRepository items;
    @Autowired InMemoryProductLookupStub products;

    @BeforeEach
    void fixtures() {
        cleanup();
        products.register(new ProductSnapshot(501L, "옵션 상품", null, SELLER, true));
        products.register(new ProductSnapshot(502L, "판매 준비 상품", null, SELLER, false));
        for (long id : List.of(10101L, 10102L, 10103L)) {
            long product = id == 10103L ? 502L : 501L;
            jdbc.update(
                    "INSERT INTO sku(id,product_id,price,available,reserved,sale_status,version,snapshot_version,product_name_snapshot,created_at,updated_at) VALUES(?,?,1000,10,0,'ON_SALE',0,1,'상품',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id,
                    product);
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM cart_item WHERE member_id IN (?,?)", OWNER, OTHER);
        jdbc.update("DELETE FROM sku WHERE id IN (10101,10102,10103)");
        products.clear();
    }

    @Test
    @DisplayName("같은 상품의 두 SKU를 개별 수량으로 담고 조회해도 재고를 예약하지 않는다")
    void groupsPersistedSkuItemsWithoutReservation() throws Exception {
        add(10101L, 2);
        add(10102L, 5);

        mvc.perform(get("/v1/cart/items").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(501))
                .andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].items[0].skuId").value(10102))
                .andExpect(jsonPath("$[0].items[0].quantity").value(5))
                .andExpect(jsonPath("$[0].items[1].quantity").value(2));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(reserved) FROM sku WHERE id IN (10101,10102)",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(available) FROM sku WHERE id IN (10101,10102)",
                                Integer.class))
                .isEqualTo(20);
    }

    @Test
    @DisplayName("한 SKU만 수량 변경·삭제하고 다른 회원의 접근과 다른 SKU 영향은 막는다")
    void mutatesOnlyOwnedSelectedSku() throws Exception {
        long first = add(10101L, 2);
        long second = add(10102L, 5);
        mvc.perform(
                        patch("/v1/cart/items/{id}", first)
                                .header("Authorization", bearer(OTHER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":7}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        patch("/v1/cart/items/{id}", first)
                                .header("Authorization", bearer(OWNER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"quantity\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(7));
        mvc.perform(delete("/v1/cart/items/{id}", first).header("Authorization", bearer(OWNER)))
                .andExpect(status().isNoContent());
        assertThat(items.findById(second).orElseThrow().getQuantity()).isEqualTo(5);
        mvc.perform(get("/v1/cart/items").header("Authorization", bearer(OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("SKU 중복은 누적하지 않고 다중 SKU의 기존 상품 ID 담기도 임의 선택하지 않는다")
    void rejectsDuplicateAndAmbiguousProductRequests() throws Exception {
        long existing = add(10101L, 2);
        mvc.perform(
                        post("/v1/cart/items")
                                .header("Authorization", bearer(OWNER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"skuId\":10101,\"quantity\":3}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/v1/cart/items")
                                .header("Authorization", bearer(OWNER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":501,\"quantity\":1}"))
                .andExpect(status().isConflict());
        assertThat(items.findById(existing).orElseThrow().getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("미매핑 항목을 보존하고 상품 전체 판매가 꺼진 SKU도 구매 불가로 표시한다")
    void preservesUnavailableItemsAndProductSwitch() throws Exception {
        CartItem legacy = items.saveAndFlush(new CartItem(OWNER, 999L, 8));
        add(10103L, 2);

        mvc.perform(get("/v1/cart/items").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(502))
                .andExpect(jsonPath("$[0].saleEnabled").value(false))
                .andExpect(jsonPath("$[0].items[0].purchasable").value(false))
                .andExpect(jsonPath("$[1].productId").value(999))
                .andExpect(jsonPath("$[1].items[0].quantity").value(8))
                .andExpect(jsonPath("$[1].items[0].purchasable").value(false));
        assertThat(items.existsById(legacy.getId())).isTrue();
    }

    private long add(long skuId, int quantity) throws Exception {
        String body = "{\"skuId\":" + skuId + ",\"quantity\":" + quantity + "}";
        var result =
                mvc.perform(
                                post("/v1/cart/items")
                                        .header("Authorization", bearer(OWNER))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }
}
