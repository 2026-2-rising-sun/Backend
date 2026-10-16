package com.shoppinglive.commerce.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.coupon.service.CouponCreationService;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:coupon_management_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(
        scripts = {
            "/db/migration/V5__coupon_definitions.sql",
            "/db/migration/V17__coupon_display_snapshot_version.sql"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CouponManagementHttpTest extends CommerceSecurityTestSupport {
    @Autowired CouponCreationService creation;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM coupon_target");
        jdbc.update("DELETE FROM coupon_definition");
        shopping.clear();
    }

    @DisplayName("쿠폰 관리 HTTP 요청은 소유자·버전·역할을 검증한다")
    @Test
    void managementHttpValidatesOwnerVersionAndRole() throws Exception {
        Instant start = Instant.now().plusSeconds(3600), end = start.plusSeconds(3600);
        shopping.register(new ProductSnapshot(1L, "상품", null, SELLER));
        var coupon = creation.create(SELLER, "original", 100, 1, start, end, end, List.of(1L));
        mvc.perform(get("/v1/seller/coupons")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/seller/coupons").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/v1/seller/coupons/{id}", coupon.id())
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + TOKENS.token(MEMBER_A, Set.of("USER", "SELLER"))))
                .andExpect(status().isForbidden());
        var body =
                Map.of(
                        "version",
                        0,
                        "settings",
                        Map.of(
                                "name",
                                "edited",
                                "fixedDiscount",
                                200,
                                "issuanceLimit",
                                2,
                                "startsAt",
                                start.toString(),
                                "endsAt",
                                end.toString(),
                                "expiresAt",
                                end.toString(),
                                "productIds",
                                List.of(1L)));
        mvc.perform(
                        patch("/v1/seller/coupons/{id}", coupon.id())
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(
                        patch("/v1/seller/coupons/{id}", coupon.id())
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
        mvc.perform(get("/v1/seller/coupons").header("Authorization", sellerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("edited"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
