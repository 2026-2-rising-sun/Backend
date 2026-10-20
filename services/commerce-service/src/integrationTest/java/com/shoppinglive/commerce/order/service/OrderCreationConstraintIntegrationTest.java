package com.shoppinglive.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:order_constraints;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class OrderCreationConstraintIntegrationTest extends CommerceSecurityTestSupport {
    private static final long PRODUCT_ID = 8401L;
    private static final String COLLIDING_NUMBER = "OD-20261020-000001";
    private static final String NEW_NUMBER = "OD-20261020-000002";

    @Autowired private OrderCreationService service;
    @Autowired private OrderJpaRepository orders;
    @Autowired private SalesJpaRepository sales;
    @Autowired private SalesStockJpaRepository stocks;
    @Autowired private InMemoryShoppingClientStub shopping;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private OrderNumberGenerator numbers;
    private Long salesId;

    @BeforeEach
    void seed() {
        cleanup();
        shopping.register(new ProductSnapshot(PRODUCT_ID, "주문 제약 검증 상품", null));
        salesId = sales.saveAndFlush(new Sales(PRODUCT_ID, 1000L, SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(salesId, 5, 0));
        given(numbers.generate()).willReturn(COLLIDING_NUMBER);
        service.create(command(MEMBER_A), "first");
    }

    @AfterEach
    void cleanup() {
        orders.deleteAll();
        jdbc.update("DELETE FROM payment_group");
        stocks.deleteAll();
        sales.deleteAll();
        jdbc.update("DELETE FROM member_purchase_guard");
        shopping.clear();
    }

    @Test
    @DisplayName("실제 주문번호 제약이 충돌하면 롤백 후 새 번호로 주문과 재고를 한 번 저장한다")
    void retriesRealOrderNumberViolationInNewTransaction() {
        given(numbers.generate()).willReturn(COLLIDING_NUMBER, NEW_NUMBER);

        var result = service.create(command(MEMBER_B), "second");

        assertThat(result.created()).isTrue();
        assertThat(result.order().getOrderNumber()).isEqualTo(NEW_NUMBER);
        assertThat(orders.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Integer.class))
                .isEqualTo(2);
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(3);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isEqualTo(2);
        verify(numbers, times(3)).generate();
    }

    @Test
    @DisplayName("실제 주문번호 충돌이 반복되면 새 주문과 결제 묶음과 재고 예약을 남기지 않는다")
    void rollsBackAllFailedOrderNumberAttempts() {
        assertThatThrownBy(() -> service.create(command(MEMBER_B), "second"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(orders.count()).isEqualTo(1);
        assertThat(orders.findByMemberIdAndIdempotencyKey(MEMBER_B, "second")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Integer.class))
                .isEqualTo(1);
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(4);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isEqualTo(1);
        verify(numbers, times(4)).generate();
    }

    private CreateOrderCommand command(String memberId) {
        return new CreateOrderCommand(PRODUCT_ID, 1, "구매자", "01012345678", memberId, null);
    }
}
