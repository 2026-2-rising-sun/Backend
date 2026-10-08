package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:coupon_creation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts="/db/migration/V5__coupon_definitions.sql", executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CouponCreationHttpTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired InMemoryShoppingClientStub shopping;
    private final Instant start = Instant.now().plus(Duration.ofDays(1));

    @BeforeEach
    void products() {
        shopping.register(new ProductSnapshot(1L, "본인 상품", null, SELLER));
        shopping.register(new ProductSnapshot(2L, "다른 상품", null, MEMBER_A));
        shopping.register(new ProductSnapshot(3L, "미지정 상품", null));
    }

    @AfterEach
    void clean() { jdbc.update("DELETE FROM coupon_target"); jdbc.update("DELETE FROM coupon_definition"); shopping.clear(); }

    private Map<String,Object> request() {
        return new HashMap<>(Map.of("name","쿠폰","fixedDiscount",1000,"issuanceLimit",10000,
            "startsAt",start.toString(),"endsAt",start.plus(Duration.ofHours(720)).toString(),
            "expiresAt",start.plus(Duration.ofHours(720)).toString(),"productIds",List.of(1L)));
    }

    @Test
    void createsAtPolicyMaximumAndStoresAuthenticatedSellerAndTargets() throws Exception {
        mvc.perform(post("/v1/seller/coupons").header("Authorization",adminBearer())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.sellerId").value(SELLER))
            .andExpect(jsonPath("$.data.issuanceLimit").value(10000));
        assertThat(jdbc.queryForObject("SELECT seller_id FROM coupon_definition",String.class)).isEqualTo(SELLER);
        assertThat(jdbc.queryForObject("SELECT product_id FROM coupon_target",Long.class)).isEqualTo(1L);
    }

    @Test
    void rejectsOtherAndUnassignedTargetsWithoutPersistingPartialCoupon() throws Exception {
        for (long id : List.of(2L,3L)) {
            var body=request();body.put("productIds",List.of(1L,id));
            mvc.perform(post("/v1/seller/coupons").header("Authorization",adminBearer())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_definition",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_target",Integer.class)).isZero();
    }

    @Test
    void rejectsExcessQuantityAndNonSeller() throws Exception {
        var body=request();body.put("issuanceLimit",10001);
        mvc.perform(post("/v1/seller/coupons").header("Authorization",adminBearer())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/seller/coupons").header("Authorization",bearer(MEMBER_A))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/v1/seller/coupons").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(request()))).andExpect(status().isUnauthorized());
    }
}
