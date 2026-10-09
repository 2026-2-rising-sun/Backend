package com.shoppinglive.commerce.payments;

import static org.assertj.core.api.Assertions.*;
import com.shoppinglive.commerce.payments.infrastructure.PaymentRecoveryStore;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="COMMERCE_TEST_POSTGRES_URL",matches=".+")
class PaymentRecoveryStorePostgresTest {
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PaymentRecoveryStore store;
    private static final Duration LEASE=Duration.ofMinutes(1);
    @BeforeEach void setup() {
        String schema="payment_recovery_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER","postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD","postgres"));
        flyway=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("13").load().migrate();
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        jdbc.update("INSERT INTO sales_info(id,product_id,price,status,created_at,updated_at) VALUES(1,1,10000,'ON_SALE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO orders(id,order_number,sales_info_id,quantity,unit_price,total_amount,status,buyer_name,buyer_phone,product_name_snapshot,member_id,payable_amount,created_at,updated_at) VALUES(1,'store-order',1,1,10000,10000,'PAYMENT_CONFIRMING','member','010','product','member',10000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        String[] statuses={"PROCESSING","UNKNOWN","SUCCESS","PENDING"};
        for(int i=0;i<4;i++) insert(i+1,statuses[i]);
        flyway.migrate();
        insert(5,"PROCESSING");
        store=new PaymentRecoveryStore(jdbc,manager);
    }
    void insert(long id,String status) {
        jdbc.update("INSERT INTO payment_attempt(id,order_id,scenario,status,requested_at,scheduled_resolve_at,created_at,updated_at) VALUES(?,1,'INSTANT_SUCCESS',?,CURRENT_TIMESTAMP-INTERVAL '8 days',CURRENT_TIMESTAMP-INTERVAL '1 second',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,status);
    }
    @AfterEach void cleanup(){if(flyway!=null)flyway.clean();}
    @Test void migrationKeepsBusinessDataAndLimitsMetadata() {
        assertThat(jdbc.queryForList("SELECT status FROM payment_attempt ORDER BY id",String.class))
            .containsExactly("PROCESSING","UNKNOWN","SUCCESS","PENDING","PROCESSING");
        assertThat(jdbc.queryForObject("SELECT execution_started_at=requested_at FROM payment_attempt WHERE id=1",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT execution_started_at IS NULL FROM payment_attempt WHERE id=5",Boolean.class)).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE payment_attempt SET retry_count=4 WHERE id=5")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE payment_attempt SET lease_token='orphan' WHERE id=5")).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void simultaneousWorkersHaveOneOwner() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=java.util.stream.IntStream.range(0,4).mapToObj(i->pool.submit(()->{start.await();return new PaymentRecoveryStore(jdbc,manager).claim(5,LEASE);})).toList();
            start.countDown();int owners=0;
            for(var f:futures)if(f.get(15,TimeUnit.SECONDS).isPresent())owners++;
            assertThat(owners).isEqualTo(1);
        }
    }
    @Test void budgetSurvivesRecreationAndCallerRollback() {
        var lease=store.claim(5,LEASE).orElseThrow();
        new TransactionTemplate(manager).executeWithoutResult(tx->{assertThat(store.beginInvocation(lease)).contains(0);tx.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT execution_started_at IS NOT NULL FROM payment_attempt WHERE id=5",Boolean.class)).isTrue();
        assertThat(store.unknown(lease,Duration.ZERO)).isTrue();
        for(int i=1;i<=3;i++) {
            store=new PaymentRecoveryStore(jdbc,manager);var next=store.claim(5,LEASE).orElseThrow();
            assertThat(store.beginInvocation(next)).contains(i);assertThat(store.unknown(next,Duration.ZERO)).isTrue();
        }
        var exhausted=new PaymentRecoveryStore(jdbc,manager).claim(5,LEASE).orElseThrow();
        assertThat(store.beginInvocation(exhausted)).isEmpty();
        assertThat(store.unknown(exhausted,Duration.ofMinutes(1))).isTrue();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt WHERE id=5",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE id=5",String.class)).isEqualTo("UNKNOWN");
        assertThat(store.claim(5,LEASE)).isEmpty();
    }
    @Test void expiredOwnerCannotCompleteOrRescheduleAfterReclaim() {
        var old=store.claim(5,LEASE).orElseThrow();
        jdbc.update("UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=5");
        var current=store.claim(5,LEASE).orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(store.complete(old,"SUCCESS")).isFalse();assertThat(store.unknown(old,Duration.ZERO)).isFalse();
        assertThat(store.complete(current,"SUCCESS")).isTrue();assertThat(store.claim(5,LEASE)).isEmpty();
    }
    @Test void lockedCandidateDoesNotBlockAnotherAndCompatibilityPendingIsExcluded() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.queryForObject("SELECT id FROM payment_attempt WHERE id=1 FOR UPDATE",Long.class);locked.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            try{assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();assertThat(store.claimDue(10,LEASE)).extracting(PaymentRecoveryStore.Lease::id).containsExactly(2L,5L);}finally{release.countDown();}
            holder.get(15,TimeUnit.SECONDS);
        }
        assertThat(store.claim(3,LEASE)).isEmpty();assertThat(store.claim(4,LEASE)).isEmpty();
    }
    @Test void completionRollsBackTogetherWithBusinessWrite() {
        var lease=store.claim(5,LEASE).orElseThrow();
        new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.update("UPDATE orders SET status='PAID' WHERE id=1");assertThat(store.complete(lease,"SUCCESS")).isTrue();tx.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempt WHERE id=5",String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=1",String.class)).isEqualTo("PAYMENT_CONFIRMING");
        assertThat(store.owns(lease)).isTrue();
    }
}
