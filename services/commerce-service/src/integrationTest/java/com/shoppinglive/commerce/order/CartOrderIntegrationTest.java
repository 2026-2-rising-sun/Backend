package com.shoppinglive.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.order.dto.CartOrderCommand;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.exception.CartItemNotFoundException;
import com.shoppinglive.commerce.order.repository.CartItemRepository;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.order.service.CartService;
import com.shoppinglive.commerce.order.service.OrderCreationService;
import com.shoppinglive.commerce.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class CartOrderIntegrationTest
        extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {
    private static final String MEMBER_A = "11111111-1111-4111-8111-111111111111";
    private static final String MEMBER_B = "22222222-2222-4222-8222-222222222222";
    @Autowired CartService cart;
    @Autowired CartItemRepository items;
    @Autowired OrderCreationService creation;
    @Autowired OrderJpaRepository orders;
    @Autowired PaymentAttemptJpaRepository payments;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired TransactionTemplate transaction;
    @Autowired com.shoppinglive.commerce.payment.service.PaymentService paymentService;
    private Long salesId;

    @BeforeEach
    void seed() {
        clean();
        shopping.register(new ProductSnapshot(1L, "상품", null));
        shopping.register(new ProductSnapshot(2L, "다른 상품", null));
        salesId = sales.save(new Sales(1L, 1000L, SalesStatus.ON_SALE)).getId();
        stocks.save(new SalesStock(salesId, 10, 0));
    }

    @AfterEach
    void clean() {
        payments.deleteAll();
        orders.deleteAll();
        items.deleteAll();
        stocks.deleteAll();
        sales.deleteAll();
        shopping.clear();
    }

    private CartOrderCommand request(long amount) {
        return new CartOrderCommand("회원", "01012345678", amount);
    }

    @DisplayName("장바구니는 재고를 예약하지 않고 결제 성공 시 선택 항목만 멱등하게 삭제한다")
    @Test
    void cartDoesNotReserveAndSuccessfulPaymentDeletesOnlySelectedItemWithReplay() {
        var selected = cart.add(MEMBER_A, 1L, 2);
        var other = cart.add(MEMBER_A, 2L, 1);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isZero();
        var first = creation.createFromCart(MEMBER_A, selected.getId(), request(2000), "cart-key");
        assertThat(first.created()).isTrue();
        assertThat(items.findAll())
                .extracting(item -> item.getId())
                .containsExactly(selected.getId(), other.getId());
        var replay = creation.createFromCart(MEMBER_A, selected.getId(), request(2000), "cart-key");
        assertThat(replay.created()).isFalse();
        assertThat(replay.order().getId()).isEqualTo(first.order().getId());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isEqualTo(2);
        paymentService.startPayment(first.order().getOrderNumber(), MEMBER_A);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            orders.findById(first.order().getId())
                                                    .orElseThrow()
                                                    .getStatus())
                                    .isEqualTo(
                                            com.shoppinglive.commerce.order.entity.OrderStatus
                                                    .PAID);
                            assertThat(items.findAll())
                                    .extracting(item -> item.getId())
                                    .containsExactly(other.getId());
                        });
        assertThatThrownBy(
                        () ->
                                creation.createFromCart(
                                        MEMBER_A, selected.getId(), request(3000), "cart-key"))
                .isInstanceOf(BusinessException.class);
    }

    @DisplayName("다른 회원의 장바구니 항목은 조회·수정·삭제·주문할 수 없다")
    @Test
    void anotherMemberCannotReadChangeDeleteOrOrderSelectedItem() {
        var selected = cart.add(MEMBER_A, 1L, 2);
        assertThat(cart.list(MEMBER_B)).isEmpty();
        assertThatThrownBy(() -> cart.changeQuantity(MEMBER_B, selected.getId(), 3))
                .isInstanceOf(CartItemNotFoundException.class);
        assertThatThrownBy(() -> cart.delete(MEMBER_B, selected.getId()))
                .isInstanceOf(CartItemNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                creation.createFromCart(
                                        MEMBER_B, selected.getId(), request(2000), "key"))
                .isInstanceOf(CartItemNotFoundException.class);
        assertThat(orders.count()).isZero();
        assertThat(items.findById(selected.getId()).orElseThrow().getQuantity()).isEqualTo(2);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isZero();
    }

    @DisplayName("재고가 부족하면 주문을 롤백하고 장바구니를 유지한다")
    @Test
    void insufficientStockRollsBackOrderAndPreservesCart() {
        var selected = cart.add(MEMBER_A, 1L, 11);
        assertThatThrownBy(
                        () ->
                                creation.createFromCart(
                                        MEMBER_A, selected.getId(), request(11000), "key"))
                .isInstanceOf(BusinessException.class);
        assertThat(orders.count()).isZero();
        assertThat(items.existsById(selected.getId())).isTrue();
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(10);
    }

    @DisplayName("주문 시 최신 가격과 판매 상태를 다시 확인하고 장바구니 항목을 유지한다")
    @Test
    void latestPriceAndSalesStatusAreRecheckedWithoutRemovingItem() {
        var selected = cart.add(MEMBER_A, 1L, 1);
        assertThatThrownBy(
                        () ->
                                creation.createFromCart(
                                        MEMBER_A, selected.getId(), request(999), "key"))
                .isInstanceOf(BusinessException.class);
        transaction.executeWithoutResult(
                status -> sales.transitionStatus(salesId, "ON_SALE", "PRIVATE"));
        assertThatThrownBy(
                        () ->
                                creation.createFromCart(
                                        MEMBER_A, selected.getId(), request(1000), "key"))
                .isInstanceOf(BusinessException.class);
        assertThat(items.existsById(selected.getId())).isTrue();
        assertThat(orders.count()).isZero();
    }

    @DisplayName("같은 멱등 키도 회원이 다르면 독립적으로 사용한다")
    @Test
    void sameIdempotencyKeyIsIndependentForDifferentMembers() {
        var a =
                creation.create(
                        new CreateOrderCommand(1L, 1, "회원", "01012345678", MEMBER_A, null), "same");
        var b =
                creation.create(
                        new CreateOrderCommand(1L, 1, "회원", "01012345678", MEMBER_B, null), "same");
        assertThat(a.order().getId()).isNotEqualTo(b.order().getId());
        assertThat(orders.count()).isEqualTo(2);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isEqualTo(2);
    }

    @DisplayName("장바구니 주문을 동시에 재전송해도 주문과 재고 예약은 한 번만 생성한다")
    @Test
    void concurrentCartReplayCreatesOneOrderAndReservesOnce() throws Exception {
        var item = cart.add(MEMBER_A, 1L, 2);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var numbers = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(6);
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) {
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                numbers.add(
                                        creation.createFromCart(
                                                        MEMBER_A,
                                                        item.getId(),
                                                        request(2000),
                                                        "same")
                                                .order()
                                                .getOrderNumber());
                            } catch (Throwable failure) {
                                failures.add(failure);
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).isEmpty();
        assertThat(numbers.stream().distinct().count()).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(1);
        assertThat(items.existsById(item.getId())).isTrue();
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isEqualTo(2);
    }
}
