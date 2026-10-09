package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.*;
import com.shoppinglive.commerce.coupons.application.CouponClaimService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named="COMMERCE_TEST_POSTGRES_URL",matches=".+")
class CouponClaimPostgresTest {
    private void verify(int limit,java.util.function.Consumer<DriverManagerDataSource> test) {
        String schema="coupon_claim_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER","postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD","postgres"));
        var flyway=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        try {
            flyway.migrate();Instant now=Instant.now();var jdbc=new JdbcTemplate(ds);
            jdbc.update("INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,starts_at,ends_at,expires_at,created_at) VALUES ('coupon','seller','쿠폰',100,?,?,?,?,?)",
                limit,Timestamp.from(now.minusSeconds(60)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now.plusSeconds(3600)),Timestamp.from(now));
            jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES ('coupon',1)");test.accept(ds);
        } finally { flyway.clean(); }
    }

    private List<String> claims(DriverManagerDataSource ds,boolean sameMember) {
        var service=new CouponClaimService(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));
        var ready=new CountDownLatch(20);var go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(20)) {
            var futures=IntStream.range(0,20).mapToObj(i->executor.submit(()->{
                ready.countDown();go.await();
                try { return service.claim(sameMember?"member":"member-"+i,"coupon").coupon().id(); }
                catch(BusinessException error) { assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT);return null; }
            })).toList();
            try {
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
                return futures.stream().map(f->{try { return f.get(20,TimeUnit.SECONDS); }
                    catch(Exception error) { throw new AssertionError(error); }}).filter(java.util.Objects::nonNull).toList();
            } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); }
            finally { go.countDown(); }
        }
    }

    @Test
    void finalCouponHasExactlyOneWinnerAcrossTwentyMembers() {
        verify(1,ds->{
            assertThat(claims(ds,false)).hasSize(1);var jdbc=new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_definition",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon",Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void sameMemberConcurrentClaimsReturnOneReceiptWithoutConsumingExtraQuantity() {
        verify(10000,ds->{
            var result=claims(ds,true);assertThat(result).hasSize(20);assertThat(result.stream().distinct()).hasSize(1);
            assertThat(new JdbcTemplate(ds).queryForObject("SELECT issued_count FROM coupon_definition",Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void databaseFailureRollsBackCounterAndAllowsRetry() {
        verify(1,ds->{
            var jdbc=new JdbcTemplate(ds);
            var failing=new JdbcTemplate(ds) {
                @Override public int update(String sql,Object...args) {
                    if(sql.startsWith("INSERT INTO member_coupon"))throw new DataIntegrityViolationException("injected");
                    return super.update(sql,args);
                }
            };
            assertThatThrownBy(()->new CouponClaimService(failing,new DataSourceTransactionManager(ds)).claim("member","coupon"))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_definition",Integer.class)).isZero();
            assertThat(new CouponClaimService(jdbc,new DataSourceTransactionManager(ds)).claim("member","coupon").created()).isTrue();
        });
    }
}
