package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:member_coupon_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts={"/db/migration/V5__coupon_definitions.sql", "/db/migration/V6__member_coupons.sql"},
    executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class MemberCouponHttpTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final String coupon="33333333-3333-4333-8333-333333333334";

    @BeforeEach
    void coupon() {
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,created_at)
            VALUES (?,?,?,100,2,0,?,?,?,?)
            """,coupon,SELLER,"회원 쿠폰",Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),
            Timestamp.from(now.plusSeconds(7200)),Timestamp.from(now));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?",coupon);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?",coupon);
    }

    @Test
    void claimIsIdempotentAndReceiptIsOnlyVisibleToOwningMember() throws Exception {
        var first=mvc.perform(post("/v1/coupons/{id}/claims",coupon).header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("AVAILABLE"))
            .andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(first).at("/data/id").asText();
        mvc.perform(post("/v1/coupons/{id}/claims",coupon).header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get("/v1/me/coupons").header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/v1/me/coupons").header("Authorization",bearer(MEMBER_B)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_definition WHERE id=?",Integer.class,coupon)).isEqualTo(1);
    }

    @Test
    void availableListAndClaimRequireMemberRole() throws Exception {
        mvc.perform(get("/v1/coupons").header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(coupon));
        mvc.perform(get("/v1/coupons"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/coupons").header("Authorization","Bearer " + TOKENS.token(MEMBER_A,Set.of("ADMIN"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/v1/coupons/{id}/claims",coupon).header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isCreated());
    }

    @Test
    void rejectsInvalidPagingAndMissingCoupon() throws Exception {
        mvc.perform(get("/v1/me/coupons?page=-1").header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/coupons?size=101").header("Authorization",bearer(MEMBER_A)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/coupons/{id}/claims","33333333-3333-4333-8333-333333333335")
            .header("Authorization",bearer(MEMBER_A)).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound());
    }
}
