package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.shoppinglive.commerce.coupons.application.CouponCreationService;
import com.shoppinglive.commerce.coupons.application.CouponManagementService;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named="COMMERCE_TEST_POSTGRES_URL",matches=".+")
class CouponManagementPostgresTest {
    private void verify(java.util.function.Consumer<DataSource> test) {
        String schema="coupon_management_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER","postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD","postgres"));
        var flyway=Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        try { flyway.migrate();test.accept(ds); } finally { flyway.clean(); }
    }

    private ShoppingClient shopping() {
        ShoppingClient client=mock(ShoppingClient.class);
        for(long id:List.of(1L,2L))when(client.findProduct(id)).thenReturn(Optional.of(new ProductSnapshot(id,"상품",null,"seller")));
        return client;
    }

    @Test
    void detailKeepsDefinitionAndTargetsInOneSnapshotAcrossConcurrentEdit() {
        verify(ds -> {
            var jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);var shopping=shopping();
            Instant start=Instant.now().plusSeconds(3600),end=start.plusSeconds(3600);
            var coupon=new CouponCreationService(jdbc,shopping,tx).create("seller","old",100,1,start,end,end,List.of(1L));
            var paused=new PausingJdbc(ds);
            var read=new CouponManagementService(paused,shopping,tx);
            var write=new CouponManagementService(jdbc,shopping,tx);
            try(var executor=Executors.newSingleThreadExecutor()) {
                var result=executor.submit(() -> read.get("seller",coupon.id()));
                try {
                    assertThat(paused.selected.await(10,TimeUnit.SECONDS)).isTrue();
                    write.update("seller",coupon.id(),0,"new",200,2,start,end,end,List.of(2L));
                    paused.resume.countDown();
                    var snapshot=result.get(10,TimeUnit.SECONDS);
                    assertThat(snapshot.name()).isEqualTo("old");
                    assertThat(snapshot.version()).isZero();
                    assertThat(snapshot.productIds()).containsExactly(1L);
                    assertThat(write.get("seller",coupon.id()).productIds()).containsExactly(2L);
                } catch(Exception error) { throw new AssertionError(error); }
                finally { paused.resume.countDown(); }
            }
        });
    }

    @Test
    void concurrentVersionsHaveOneWinnerOnPostgres() {
        verify(ds -> {
            var jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);var shopping=shopping();
            Instant start=Instant.now().plusSeconds(3600),end=start.plusSeconds(3600);
            var coupon=new CouponCreationService(jdbc,shopping,tx).create("seller","old",100,1,start,end,end,List.of(1L));
            var service=new CouponManagementService(jdbc,shopping,tx);
            var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var results=List.of("first","second").stream().map(name -> executor.submit(() -> {
                    ready.countDown();go.await();
                    try { service.update("seller",coupon.id(),0,name,100,1,start,end,end,List.of(1L));return true; }
                    catch(BusinessException error) {
                        assertThat(error.errorCode()).isEqualTo(com.shoppinglive.common.core.ErrorCode.CONFLICT);return false;
                    }
                })).toList();
                try {
                    assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
                    assertThat(results.get(0).get(10,TimeUnit.SECONDS)).isNotEqualTo(results.get(1).get(10,TimeUnit.SECONDS));
                } catch(Exception error) { throw new AssertionError(error); }
                finally { go.countDown(); }
            }
            assertThat(service.get("seller",coupon.id()).version()).isEqualTo(1);
        });
    }

    private static class PausingJdbc extends JdbcTemplate {
        final CountDownLatch selected=new CountDownLatch(1),resume=new CountDownLatch(1);
        final AtomicBoolean pause=new AtomicBoolean(true);
        PausingJdbc(DataSource ds) { super(ds); }
        @Override
        public <T> List<T> queryForList(String sql,Class<T> type,Object...args) {
            if(sql.startsWith("SELECT product_id") && pause.compareAndSet(true,false)) {
                selected.countDown();
                try { if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("reader not resumed"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
            }
            return super.queryForList(sql,type,args);
        }
    }
}
