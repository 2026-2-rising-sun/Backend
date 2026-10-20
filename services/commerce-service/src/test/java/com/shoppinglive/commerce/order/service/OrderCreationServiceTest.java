package com.shoppinglive.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.client.shopping.ProductNotFoundException;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.client.shopping.ShoppingClient;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.dto.OrderCreationResult;
import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.exception.OrderAmountMismatchException;
import com.shoppinglive.commerce.order.exception.OrderNotAcceptableException;
import com.shoppinglive.commerce.order.repository.CartItemRepository;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.exception.InsufficientStockException;
import com.shoppinglive.commerce.sales.exception.SalesNotFoundException;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long SALES_ID = 10L;
    private static final long PRICE = 15_000L;

    @Mock private OrderJpaRepository orderRepository;

    @Mock private SalesJpaRepository salesRepository;

    @Mock private SalesStockJpaRepository salesStockRepository;

    @Mock private ShoppingClient shoppingClient;

    @Mock private OrderNumberGenerator orderNumberGenerator;

    @Mock private CartItemRepository cartItems;

    @Mock private TransactionTemplate transactionTemplate;

    private OrderCreationService orderCreationService;

    @Mock private com.shoppinglive.commerce.order.service.PurchaseGuard purchaseGuard;
    @Mock private com.shoppinglive.commerce.order.service.PaymentGroupService paymentGroups;

    @BeforeEach
    void setUp() {
        orderCreationService =
                new OrderCreationService(
                        orderRepository,
                        salesRepository,
                        salesStockRepository,
                        shoppingClient,
                        orderNumberGenerator,
                        cartItems,
                        transactionTemplate,
                        purchaseGuard,
                        paymentGroups,
                        Duration.ofMinutes(15));
    }

    private CreateOrderCommand command(int quantity, Long expectedTotalAmount) {
        return new CreateOrderCommand(
                PRODUCT_ID,
                quantity,
                "홍길동",
                "010-1234-5678",
                "11111111-1111-4111-8111-111111111111",
                expectedTotalAmount);
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionInline() {
        given(transactionTemplate.execute(any()))
                .willAnswer(
                        invocation ->
                                ((TransactionCallback<Object>) invocation.getArgument(0))
                                        .doInTransaction(new SimpleTransactionStatus()));
    }

    private void productExists() {
        given(shoppingClient.findProduct(PRODUCT_ID))
                .willReturn(Optional.of(new ProductSnapshot(PRODUCT_ID, "테스트 상품", null)));
    }

    private void salesExists(SalesStatus status) {
        Sales sales = new Sales(PRODUCT_ID, PRICE, status);
        ReflectionTestUtils.setField(sales, "id", SALES_ID);
        given(salesRepository.findByProductId(PRODUCT_ID)).willReturn(Optional.of(sales));
    }

    /** 배정 성공 + 저장까지 이어지는 기본 경로를 구성한다. */
    private void reservationSucceeds(int remainingAvailable) {
        given(salesStockRepository.reserve(eq(SALES_ID), anyInt())).willReturn(1);
        given(salesStockRepository.findById(SALES_ID))
                .willReturn(Optional.of(new SalesStock(SALES_ID, remainingAvailable, 1)));
        given(orderNumberGenerator.generate()).willReturn("OD-20260922-000001");
        given(orderRepository.saveAndFlush(any(Order.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    // ----- 정상 생성 -----

    @Test
    @DisplayName("주문을 생성하고 재고를 예약한다")
    void createsOrderAndReservesStock() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        OrderCreationResult result = orderCreationService.create(command(2, null), null);

        assertThat(result.created()).isTrue();
        verify(salesStockRepository).reserve(SALES_ID, 2);

        Order order = result.order();
        assertThat(order.getOrderNumber()).isEqualTo("OD-20260922-000001");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getUnitPrice()).isEqualTo(PRICE);
        assertThat(order.getTotalAmount()).isEqualTo(30_000L);
    }

    /** 상품명·단가는 주문 시점 값으로 고정된다. 나중에 Shopping 이 이름을 바꾸거나 판매자가 가격을 올려도 이미 만들어진 주문의 영수증은 그대로여야 한다. */
    @Test
    @DisplayName("주문 당시 상품명과 단가를 스냅샷으로 저장한다")
    void snapshotsProductNameAndUnitPriceAtCreation() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        Order order = orderCreationService.create(command(2, null), null).order();

        assertThat(order.getProductNameSnapshot()).isEqualTo("테스트 상품");
        assertThat(order.getUnitPrice()).isEqualTo(PRICE);
    }

    /** 검증된 회원 식별자를 주문에 저장한다. */
    @Test
    @DisplayName("주문에 인증된 회원 소유권을 저장한다")
    void storesAuthenticatedMemberOwnership() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        Order order = orderCreationService.create(command(1, null), null).order();

        assertThat(order.getMemberId()).isEqualTo("11111111-1111-4111-8111-111111111111");
    }

    /** 결제 기한이 없으면 이정 님의 만료 스케줄러가 대상을 찾지 못해, 결제 없이 이탈한 주문의 재고가 영원히 묶인다. */
    @Test
    @DisplayName("주문 생성 시 결제 기한을 설정한다")
    void setsPaymentDeadline() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        Instant before = Instant.now();
        Order order = orderCreationService.create(command(1, null), null).order();

        assertThat(order.getExpiresAt())
                .isNotNull()
                .isAfter(before.plus(Duration.ofMinutes(14)))
                .isBefore(before.plus(Duration.ofMinutes(16)));
    }

    // ----- 품절 자동 전이 -----

    @Test
    @DisplayName("주문 예약으로 재고가 0이 되면 품절로 전이한다")
    void marksSaleSoldOutWhenReservationLeavesZeroStock() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(0);

        orderCreationService.create(command(1, null), null);

        verify(salesRepository).transitionStatus(SALES_ID, "ON_SALE", "SOLD_OUT");
    }

    @Test
    @DisplayName("주문 예약 후 재고가 남으면 품절로 전이하지 않는다")
    void keepsSaleActiveWhenStockRemains() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        orderCreationService.create(command(1, null), null);

        verify(salesRepository, never()).transitionStatus(any(), anyString(), anyString());
    }

    // ----- 판매 상태 -----

    @Test
    @DisplayName("판매중이 아니면 409로 거절하고 재고를 변경하지 않는다")
    void rejectsInactiveSaleWithoutChangingStock() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.PRIVATE);

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isInstanceOf(OrderNotAcceptableException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(salesStockRepository, never()).reserve(any(), anyInt());
    }

    @Test
    @DisplayName("판매정보가 없으면 주문 생성은 404로 응답한다")
    void returns404ForMissingSale() {
        productExists();
        executeTransactionInline();
        given(salesRepository.findByProductId(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isInstanceOf(SalesNotFoundException.class);
    }

    @Test
    @DisplayName("Shopping에 상품이 없으면 재고를 변경하기 전에 중단한다")
    void stopsBeforeStockMutationWhenShoppingProductIsMissing() {
        given(shoppingClient.findProduct(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isInstanceOf(ProductNotFoundException.class);

        verify(salesStockRepository, never()).reserve(any(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    // ----- 재고 부족 -----

    /**
     * 배정이 실패하면 트랜잭션 전체가 롤백된다. 주문 INSERT 가 배정보다 먼저 실행되지만, 같은 트랜잭션이라 "주문만 있고 재고는 그대로" 가 남지 않는다. 여기서는
     * 예외가 올라오는 것까지 확인하고, 롤백 자체는 {@code OrderCreationApiTest} 가 실제 DB 로 검증한다.
     */
    @Test
    @DisplayName("재고 예약에 실패하면 주문 생성은 409로 거절한다")
    void returns409WhenStockReservationFails() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        given(salesStockRepository.reserve(SALES_ID, 5)).willReturn(0);
        given(orderNumberGenerator.generate()).willReturn("OD-20260922-000001");
        given(orderRepository.saveAndFlush(any(Order.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> orderCreationService.create(command(5, null), null))
                .isInstanceOf(InsufficientStockException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ----- 금액 확인 -----

    @Test
    @DisplayName("확인 금액이 현재 금액과 같으면 검증을 통과한다")
    void acceptsExpectedAmountMatchingCurrentPrice() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        OrderCreationResult result = orderCreationService.create(command(2, 30_000L), null);

        assertThat(result.order().getTotalAmount()).isEqualTo(30_000L);
    }

    /** 사용자가 15,000 원인 줄 알고 눌렀는데 20,000 원이 조용히 결제되면 안 된다. */
    @Test
    @DisplayName("확인 금액이 다르면 409로 거절하고 재고를 변경하지 않는다")
    void rejectsExpectedAmountMismatchWithoutChangingStock() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);

        assertThatThrownBy(() -> orderCreationService.create(command(2, 25_000L), null))
                .isInstanceOf(OrderAmountMismatchException.class)
                .hasMessageContaining("25000")
                .hasMessageContaining("30000");

        verify(salesStockRepository, never()).reserve(any(), anyInt());
    }

    @Test
    @DisplayName("확인 금액을 생략하면 금액 비교를 생략한다")
    void skipsExpectedAmountCheckWhenOmitted() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        assertThat(orderCreationService.create(command(2, null), null).created()).isTrue();
    }

    // ----- 멱등 처리 -----

    @Test
    @DisplayName("같은 멱등 키의 재요청은 새 주문 없이 기존 주문을 반환한다")
    void returnsExistingOrderForIdempotentReplayWithoutCreatingAnother() {
        Order existing = existingOrder();
        given(salesRepository.findById(SALES_ID))
                .willReturn(Optional.of(new Sales(PRODUCT_ID, PRICE, SalesStatus.ON_SALE)));
        given(
                        orderRepository.findByMemberIdAndIdempotencyKey(
                                "11111111-1111-4111-8111-111111111111", "key-1"))
                .willReturn(Optional.of(existing));

        OrderCreationResult result = orderCreationService.create(command(2, null), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(existing);
        verify(salesStockRepository, never()).reserve(any(), anyInt());
        verify(shoppingClient, never()).findProduct(any());
    }

    /** 두 재전송이 동시에 멱등키 조회를 통과하면 한쪽은 UNIQUE 제약에 걸린다. 그 요청도 실패가 아니라 기존 주문을 받아야 한다. */
    @Test
    @DisplayName("멱등 키의 UNIQUE 충돌은 기존 주문으로 연결한다")
    void loadsExistingOrderAfterIdempotencyUniqueConflict() {
        productExists();
        Order existing = existingOrder();
        given(salesRepository.findById(SALES_ID))
                .willReturn(Optional.of(new Sales(PRODUCT_ID, PRICE, SalesStatus.ON_SALE)));
        given(
                        orderRepository.findByMemberIdAndIdempotencyKey(
                                "11111111-1111-4111-8111-111111111111", "key-1"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(existing));
        given(transactionTemplate.execute(any()))
                .willThrow(integrityViolation("23505", "uk_orders_member_idempotency"));

        OrderCreationResult result = orderCreationService.create(command(2, null), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(existing);
    }

    /** 멱등키 없이 오는 요청도 허용한다. 이때는 재전송이 별개 주문이 되는데, 이는 클라이언트가 키를 안 보낸 결과이지 서버 오류가 아니다. */
    @Test
    @DisplayName("멱등 키가 없으면 기존 주문을 조회하지 않는다")
    void skipsIdempotencyLookupWhenKeyIsMissing() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        orderCreationService.create(command(1, null), null);

        verify(orderRepository, never()).findByMemberIdAndIdempotencyKey(any(), any());
    }

    /** 주문번호는 난수라 같은 날 겹칠 수 있다. 유일성은 DB 제약이 보장하고, 서비스는 새 번호로 다시 시도한다. */
    @ParameterizedTest
    @ValueSource(strings = {"uk_orders_order_number", "\"public\".\"UK_ORDERS_ORDER_NUMBER\""})
    @DisplayName("주문번호가 충돌하면 새 번호로 재시도한다")
    void retriesWithNewNumberAfterOrderNumberCollision(String constraint) {
        productExists();
        given(transactionTemplate.execute(any()))
                .willThrow(integrityViolation("23505", constraint))
                .willAnswer(invocation -> new OrderCreationResult(existingOrder(), true));

        OrderCreationResult result = orderCreationService.create(command(1, null), null);

        assertThat(result.created()).isTrue();
    }

    @Test
    @DisplayName("주문번호 충돌이 세 번 발생하면 발급 실패를 반환한다")
    void stopsAfterThreeOrderNumberCollisions() {
        productExists();
        given(transactionTemplate.execute(any()))
                .willThrow(integrityViolation("23505", "uk_orders_order_number"));

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(transactionTemplate, times(3)).execute(any());
    }

    @ParameterizedTest
    @CsvSource({
        "23505, uk_other_unique",
        "23503, fk_orders_sales_info",
        "23502, uk_orders_order_number",
        "23514, ck_orders_quantity_positive",
        "23505, uk_orders_order_number_extra",
        "23505, PUBLIC.UK_ORDERS_ORDER_NUMBER_INDEX_8"
    })
    @DisplayName("알 수 없는 제약과 유니크 이외 오류는 재시도 없이 원래 예외를 전파한다")
    void propagatesOtherIntegrityViolations(String state, String constraint) {
        productExists();
        DataIntegrityViolationException failure = integrityViolation(state, constraint);
        given(transactionTemplate.execute(any())).willThrow(failure);

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isSameAs(failure);
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("다른 무결성 오류가 발생하면 기존 주문을 재조회해 오류를 감추지 않는다")
    void doesNotReplayOrderAfterUnrelatedIntegrityViolation() {
        productExists();
        var failure = integrityViolation("23503", "fk_orders_sales_info");
        given(transactionTemplate.execute(any())).willThrow(failure);

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), "key-1"))
                .isSameAs(failure);
        verify(orderRepository).findByMemberIdAndIdempotencyKey(anyString(), eq("key-1"));
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("제약 이름을 메시지에만 담은 오류는 원래 예외를 전파한다")
    void doesNotClassifyTopLevelMessageAsConstraint() {
        productExists();
        var failure = new DataIntegrityViolationException("uk_orders_order_number");
        given(transactionTemplate.execute(any())).willThrow(failure);

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isSameAs(failure);
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("멱등키 충돌 후 기존 주문이 없으면 원래 예외를 전파한다")
    void propagatesIdempotencyConflictWhenOrderCannotBeFound() {
        productExists();
        var failure = integrityViolation("23505", "uk_orders_member_idempotency");
        given(transactionTemplate.execute(any())).willThrow(failure);

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), "missing"))
                .isSameAs(failure);
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("멱등키 충돌 후 기존 주문의 입력이 다르면 충돌을 반환한다")
    void rejectsDifferentInputAfterIdempotencyConflict() {
        productExists();
        given(orderRepository.findByMemberIdAndIdempotencyKey(anyString(), eq("key-1")))
                .willReturn(Optional.empty(), Optional.of(existingOrder()));
        given(salesRepository.findById(SALES_ID))
                .willReturn(Optional.of(new Sales(PRODUCT_ID, PRICE, SalesStatus.ON_SALE)));
        given(transactionTemplate.execute(any()))
                .willThrow(integrityViolation("23505", "uk_orders_member_idempotency"));

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), "key-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(transactionTemplate).execute(any());
    }

    @Test
    @DisplayName("유니크 오류에 제약 이름이 없으면 원래 예외를 전파한다")
    void propagatesUniqueViolationWithoutConstraintName() {
        productExists();
        var failure = integrityViolation("23505", null);
        given(transactionTemplate.execute(any())).willThrow(failure);

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
                .isSameAs(failure);
    }

    private DataIntegrityViolationException integrityViolation(String state, String constraint) {
        return new DataIntegrityViolationException(
                "무결성 위반",
                new ConstraintViolationException(
                        "DB 제약 위반", new SQLException("DB 오류", state), constraint));
    }

    // ----- 입력 검증 -----

    @Test
    @DisplayName("필수값이 없으면 400으로 거절하고 외부·DB 조회를 하지 않는다")
    void rejectsMissingRequiredFieldsBeforeAnyLookup() {
        CreateOrderCommand noName =
                new CreateOrderCommand(
                        PRODUCT_ID,
                        1,
                        " ",
                        "010-1234-5678",
                        "11111111-1111-4111-8111-111111111111",
                        null);

        assertThatThrownBy(() -> orderCreationService.create(noName, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(shoppingClient, never()).findProduct(any());
    }

    @Test
    @DisplayName("주문 수량이 0 이하면 400으로 거절한다")
    void returns400ForNonPositiveQuantity() {
        assertThatThrownBy(() -> orderCreationService.create(command(0, null), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orderCreationService.create(command(-1, null), null))
                .isInstanceOf(BusinessException.class);
    }

    private Order existingOrder() {
        return new Order(
                "OD-20260922-000001",
                SALES_ID,
                2,
                PRICE,
                "홍길동",
                "010-1234-5678",
                "11111111-1111-4111-8111-111111111111",
                "테스트 상품",
                "key-1",
                Instant.now().plus(Duration.ofMinutes(15)));
    }

    /** 스냅샷 필드가 요청 값 그대로 저장되는지 확인용. */
    @Test
    @DisplayName("주문자 정보를 입력값 그대로 저장한다")
    void preservesBuyerInformation() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        orderCreationService.create(command(1, null), "key-1");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getBuyerName()).isEqualTo("홍길동");
        assertThat(captor.getValue().getBuyerPhone()).isEqualTo("010-1234-5678");
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("상품 조회 중 장바구니가 변경되면 주문을 생성하거나 재고를 예약하지 않는다")
    void cartChangedDuringShoppingLookupDoesNotCreateOrReserveOrder() {
        String member = "11111111-1111-4111-8111-111111111111";
        var snapshot = new com.shoppinglive.commerce.order.entity.CartItem(member, PRODUCT_ID, 1);
        var changed = new com.shoppinglive.commerce.order.entity.CartItem(member, PRODUCT_ID, 2);
        ReflectionTestUtils.setField(snapshot, "version", 0L);
        ReflectionTestUtils.setField(changed, "version", 1L);
        given(cartItems.findByIdAndMemberId(1L, member)).willReturn(Optional.of(snapshot));
        given(cartItems.lockOwned(1L, member)).willReturn(Optional.of(changed));
        productExists();
        executeTransactionInline();

        assertThatThrownBy(
                        () ->
                                orderCreationService.createFromCart(
                                        member,
                                        1L,
                                        new com.shoppinglive.commerce.order.dto.CartOrderCommand(
                                                "회원", "01012345678", PRICE),
                                        "cart-key"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(orderRepository, never()).saveAndFlush(any());
        verify(salesStockRepository, never()).reserve(any(), anyInt());
        verify(cartItems, never()).delete(any());
    }
}
