package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MemberCouponMigrationTest {
    @Test
    void retainsLifetimeUniquenessAndDatabaseQuantityConstraints() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:member_coupon_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__coupon_definitions.sql"),
            new ClassPathResource("db/migration/V6__member_coupons.sql")).execute(ds);
        var jdbc=new JdbcTemplate(ds);Instant now=Instant.now();
        jdbc.update("INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,starts_at,ends_at,expires_at,created_at) VALUES ('coupon','seller','쿠폰',100,1,?,?,?,?)",
            Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now));
        jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES ('claim','coupon','member','USED',?)",Timestamp.from(now));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES ('again','coupon','member','AVAILABLE',?)",Timestamp.from(now)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE coupon_definition SET issued_count=2 WHERE id='coupon'"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon",Integer.class)).isEqualTo(1);
    }
}
