package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.*;
import com.shoppinglive.commerce.coupons.application.CouponClaimService;
import com.shoppinglive.common.core.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CouponClaimPolicyTest {
    private final Instant start=Instant.parse("2026-10-09T00:00:00Z"),end=start.plusSeconds(3600);
    private DriverManagerDataSource database() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:claim_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__coupon_definitions.sql"),
            new ClassPathResource("db/migration/V6__member_coupons.sql")).execute(ds);
        new JdbcTemplate(ds).update("INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,starts_at,ends_at,expires_at,created_at) VALUES ('coupon','seller','쿠폰',100,2,?,?,?,?)",
            Timestamp.from(start),Timestamp.from(end),Timestamp.from(end),Timestamp.from(start));
        return ds;
    }

    @Test
    void startIsInclusiveEndIsExclusiveAndLifetimeReplayDoesNotReissue() {
        var ds=database();var jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);
        var atStart=new CouponClaimService(jdbc,manager,Clock.fixed(start,ZoneOffset.UTC));
        var receipt=atStart.claim("member","coupon");assertThat(receipt.created()).isTrue();
        var atEnd=new CouponClaimService(jdbc,manager,Clock.fixed(end,ZoneOffset.UTC));
        assertThatThrownBy(() -> atEnd.claim("other","coupon")).isInstanceOf(BusinessException.class);
        var replay=atEnd.claim("member","coupon");
        assertThat(replay.created()).isFalse();assertThat(replay.coupon().id()).isEqualTo(receipt.coupon().id());
        assertThat(replay.coupon().status()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE member_coupon SET status='USED'");
        assertThat(atEnd.claim("member","coupon").coupon().status()).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_definition",Integer.class)).isEqualTo(1);
    }

    @Test
    void failedClaimInsertRollsBackIssuanceCounter() {
        var ds=database();var jdbc=new JdbcTemplate(ds);
        var failing=new JdbcTemplate(ds) {
            @Override public int update(String sql,Object...args) {
                if(sql.startsWith("INSERT INTO member_coupon"))throw new DataIntegrityViolationException("injected failure");
                return super.update(sql,args);
            }
        };
        var service=new CouponClaimService(failing,new DataSourceTransactionManager(ds),Clock.fixed(start,ZoneOffset.UTC));
        assertThatThrownBy(() -> service.claim("member","coupon")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_definition",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon",Integer.class)).isZero();
    }
}
