package com.shoppinglive.commerce.payments;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.shoppinglive.commerce.cart.application.CartService;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.application.*;
import com.shoppinglive.commerce.payments.domain.*;
import com.shoppinglive.commerce.payments.infrastructure.*;
import com.shoppinglive.commerce.purchase.application.*;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.sales.domain.*;
import com.shoppinglive.commerce.sales.infrastructure.*;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named="COMMERCE_TEST_POSTGRES_URL",matches=".+")
class PaymentRecoveryPostgresTest extends CommerceSecurityTestSupport {
    @Autowired PaymentService service;
    @Autowired PaymentGroupService groups;
    @Autowired PaymentGroupRepository groupRows;
    @Autowired PaymentAttemptJpaRepository attempts;
    @Autowired CartService cart;
    @Autowired CartItemRepository carts;
    @Autowired OrderJpaRepository orders;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired DevPaymentScenarioRegistry scenarios;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MockPaymentEngine engine;
    @MockitoSpyBean DurableMockGateway gateway;
    @MockitoSpyBean PaymentRecoveryStore recovery;
    static final String COUPON="aaaaaaaa-1111-4111-8111-111111111111";
    long stockId;
    @BeforeEach void seed() {
        cleanup();
        shopping.register(new ProductSnapshot(99001L,"상품",null,SELLER));
        stockId=sales.saveAndFlush(new Sales(99001L,10000L,SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(stockId,5,0));
        jdbc.update("INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,created_at) VALUES(?,?,'쿠폰',1000,1,1,CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '2 hours',CURRENT_TIMESTAMP)",COUPON,SELLER);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES(?,99001)",COUPON);
        jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES(?,?,?,'AVAILABLE',CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),COUPON,MEMBER_A);
    }
    @AfterEach void cleanup() {
        if(jdbc==null)return;
        jdbc.update("DELETE FROM mock_gateway_result");attempts.deleteAll();orders.deleteAll();groupRows.deleteAll();
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?",COUPON);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?",COUPON);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?",COUPON);
        carts.deleteAll();stocks.deleteAll();sales.deleteAll();jdbc.update("DELETE FROM member_purchase_guard");shopping.clear();
    }
    PaymentAttempt start(PaymentScenario scenario) {
        var item=cart.add(MEMBER_A,99001L,1);
        var group=groups.create(MEMBER_A,List.of(new PaymentGroupService.Selection(item.getId(),item.getVersion())),"회원","010",10000,COUPON,"recovery-order").group();
        scenarios.set(group.groupNumber(),scenario);
        return groups.start(MEMBER_A,group.groupNumber(),"recovery-payment");
    }
    void due(long id){jdbc.update("UPDATE payment_attempt SET scheduled_resolve_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",id);}
    String coupon(){return jdbc.queryForObject("SELECT status FROM member_coupon WHERE coupon_id=?",String.class,COUPON);}
    void held(long id){
        assertThat(attempts.findById(id).orElseThrow().getResolvedAt()).isNull();
        assertThat(orders.findAll()).extracting(o->o.getStatus().name()).containsOnly("PAYMENT_CONFIRMING");
        assertThat(coupon()).isEqualTo("RESERVED");assertThat(stocks.findById(stockId).orElseThrow().getReserved()).isEqualTo(1);
    }
    @Test void lostSuccessResponseKeepsReservationsAndExistingApprovalAppliesWithoutRetry() {
        var p=start(PaymentScenario.SUCCESS_RESPONSE_LOST);
        assertThat(service.resolvePayment(p.getId())).isFalse();held(p.getId());
        assertThat(attempts.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        due(p.getId());assertThat(service.resolvePayment(p.getId())).isTrue();
        assertThat(coupon()).isEqualTo("USED");assertThat(stocks.findById(stockId).orElseThrow().getReserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt WHERE id=?",Integer.class,p.getId())).isZero();
        verify(gateway,times(1)).authorize(p.getId(),p.getScenario());assertThat(service.resolvePayment(p.getId())).isFalse();
    }
    @Test void lostFailureResponseDoesNotReleaseUntilAuthoritativeFailureIsRead() {
        var p=start(PaymentScenario.FAILURE_RESPONSE_LOST);
        assertThat(service.resolvePayment(p.getId())).isFalse();held(p.getId());
        due(p.getId());assertThat(service.resolvePayment(p.getId())).isTrue();
        assertThat(coupon()).isEqualTo("AVAILABLE");assertThat(stocks.findById(stockId).orElseThrow().getAvailable()).isEqualTo(5);
        assertThat(stocks.findById(stockId).orElseThrow().getReserved()).isZero();
        assertThat(attempts.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }
    @Test void fourFailedInvocationsExhaustBudgetAndRecreatedStoreOnlyQueries() {
        var p=start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        for(int i=0;i<4;i++) {
            due(p.getId());assertThat(service.resolvePayment(p.getId())).isFalse();held(p.getId());
            assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt WHERE id=?",Integer.class,p.getId())).isEqualTo(i);
            double delta=jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",Double.class,p.getId());
            assertThat(delta).isBetween(-1.0,i==3?60.0:(double)(1<<i));
        }
        verify(gateway,times(4)).authorize(p.getId(),p.getScenario());
        due(p.getId());assertThat(service.resolvePayment(p.getId())).isFalse();held(p.getId());
        verify(gateway,times(4)).authorize(p.getId(),p.getScenario());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",Double.class,p.getId())).isBetween(55.0,60.0);
    }
    @Test void queryFailureAtExhaustionKeepsOneMinuteIntervalAndDoesNotExecute() {
        var p=start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        jdbc.update("UPDATE payment_attempt SET status='UNKNOWN',execution_started_at=requested_at,retry_count=3 WHERE id=?",p.getId());
        doThrow(new IllegalStateException("provider lookup unavailable")).when(gateway).find(p.getId());
        assertThatThrownBy(()->service.resolvePayment(p.getId())).isInstanceOf(IllegalStateException.class);held(p.getId());
        verify(gateway,never()).authorize(p.getId(),p.getScenario());
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",Double.class,p.getId())).isBetween(55.0,60.0);
    }
    @Test void approvalSurvivesBusinessRollbackAndDoesNotConsumeAnotherInvocation() {
        var p=start(PaymentScenario.INSTANT_SUCCESS);jdbc.update("UPDATE sales_stock SET reserved=0 WHERE sales_info_id=?",stockId);
        assertThatThrownBy(()->service.resolvePayment(p.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(coupon()).isEqualTo("RESERVED");assertThat(orders.findAll()).extracting(o->o.getStatus().name()).containsOnly("PAYMENT_CONFIRMING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE sales_stock SET reserved=1 WHERE sales_info_id=?",stockId);due(p.getId());
        assertThat(service.resolvePayment(p.getId())).isTrue();verify(gateway,times(1)).authorize(p.getId(),p.getScenario());
        assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt WHERE id=?",Integer.class,p.getId())).isZero();assertThat(coupon()).isEqualTo("USED");
    }
    @Test void tokenExpiryAtFinalWriteRollsBackOrderStockCouponAndRetainsApproval() {
        var p=start(PaymentScenario.INSTANT_SUCCESS);
        doAnswer(inv->{jdbc.update("UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",p.getId());return inv.callRealMethod();}).when(recovery).complete(any(),eq("SUCCESS"));
        assertThatThrownBy(()->service.resolvePayment(p.getId())).isInstanceOf(IllegalStateException.class);held(p.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result",Integer.class)).isEqualTo(1);
        doCallRealMethod().when(recovery).complete(any(),eq("SUCCESS"));due(p.getId());
        assertThat(service.resolvePayment(p.getId())).isTrue();verify(gateway,times(1)).authorize(p.getId(),p.getScenario());
    }
    @Test void interruptedInvocationConsumesOneBudgetSlotBeforeProviderIsCalled() {
        var p=start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        var lease=recovery.claim(p.getId(),Duration.ofMinutes(1)).orElseThrow();
        assertThat(recovery.beginInvocation(lease)).contains(0);
        jdbc.update("UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",p.getId());
        assertThat(service.resolvePayment(p.getId())).isFalse();held(p.getId());
        assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt WHERE id=?",Integer.class,p.getId())).isEqualTo(1);
        verify(gateway,times(1)).authorize(p.getId(),p.getScenario());
    }
}
