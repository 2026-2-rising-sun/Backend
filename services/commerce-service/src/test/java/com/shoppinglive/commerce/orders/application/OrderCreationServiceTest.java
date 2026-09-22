package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.application.InsufficientStockException;
import com.shoppinglive.commerce.sales.application.SalesNotFoundException;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long SALES_ID = 10L;
    private static final long PRICE = 15_000L;

    @Mock
    private OrderJpaRepository orderRepository;

    @Mock
    private SalesJpaRepository salesRepository;

    @Mock
    private SalesStockJpaRepository salesStockRepository;

    @Mock
    private ShoppingClient shoppingClient;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TransactionTemplate transactionTemplate;

    private OrderCreationService orderCreationService;

    @BeforeEach
    void setUp() {
        orderCreationService = new OrderCreationService(
            orderRepository, salesRepository, salesStockRepository, shoppingClient,
            orderNumberGenerator, passwordEncoder, transactionTemplate, Duration.ofMinutes(15));
    }

    private CreateOrderCommand command(int quantity, Long expectedTotalAmount) {
        return new CreateOrderCommand(
            PRODUCT_ID, quantity, "홍길동", "010-1234-5678", "secret", expectedTotalAmount);
    }

    @SuppressWarnings("unchecked")
    private void executeTransactionInline() {
        given(transactionTemplate.execute(any()))
            .willAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
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
        given(passwordEncoder.encode("secret")).willReturn("hashed");
        given(orderRepository.saveAndFlush(any(Order.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    // ----- 정상 생성 -----

    @Test
    void create_주문을_만들고_재고를_배정한다() {
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

    /**
     * 상품명·단가는 주문 시점 값으로 고정된다. 나중에 Shopping 이 이름을 바꾸거나 관리자가
     * 가격을 올려도 이미 만들어진 주문의 영수증은 그대로여야 한다.
     */
    @Test
    void create_주문_당시_상품명과_단가를_스냅샷으로_남긴다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        Order order = orderCreationService.create(command(2, null), null).order();

        assertThat(order.getProductNameSnapshot()).isEqualTo("테스트 상품");
        assertThat(order.getUnitPrice()).isEqualTo(PRICE);
    }

    /**
     * 조회 비밀번호는 평문으로 저장하지 않는다.
     */
    @Test
    void create_조회_비밀번호는_해시로_저장한다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        Order order = orderCreationService.create(command(1, null), null).order();

        assertThat(order.getLookupPasswordHash()).isEqualTo("hashed");
        assertThat(order.getLookupPasswordHash()).isNotEqualTo("secret");
    }

    /**
     * 결제 기한이 없으면 이정 님의 만료 스케줄러가 대상을 찾지 못해, 결제 없이 이탈한 주문의
     * 재고가 영원히 묶인다.
     */
    @Test
    void create_결제_기한을_설정한다() {
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
    void create_재고가_0이_되면_품절로_전이한다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(0);

        orderCreationService.create(command(1, null), null);

        verify(salesRepository).transitionStatus(SALES_ID, "ON_SALE", "SOLD_OUT");
    }

    @Test
    void create_재고가_남아있으면_품절_전이하지_않는다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        orderCreationService.create(command(1, null), null);

        verify(salesRepository, never()).transitionStatus(any(), anyString(), anyString());
    }

    // ----- 판매 상태 -----

    @Test
    void create_판매중이_아니면_409이고_재고를_건드리지_않는다() {
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
    void create_판매정보가_없으면_404() {
        productExists();
        executeTransactionInline();
        given(salesRepository.findByProductId(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
            .isInstanceOf(SalesNotFoundException.class);
    }

    @Test
    void create_Shopping에_상품이_없으면_재고를_건드리기_전에_멈춘다() {
        given(shoppingClient.findProduct(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCreationService.create(command(1, null), null))
            .isInstanceOf(ProductNotFoundException.class);

        verify(salesStockRepository, never()).reserve(any(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    // ----- 재고 부족 -----

    /**
     * 배정이 실패하면 트랜잭션 전체가 롤백된다. 주문 INSERT 가 배정보다 먼저 실행되지만, 같은
     * 트랜잭션이라 "주문만 있고 재고는 그대로" 가 남지 않는다. 여기서는 예외가 올라오는 것까지
     * 확인하고, 롤백 자체는 {@code OrderCreationApiTest} 가 실제 DB 로 검증한다.
     */
    @Test
    void create_배정에_실패하면_409를_던진다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        given(salesStockRepository.reserve(SALES_ID, 5)).willReturn(0);
        given(orderNumberGenerator.generate()).willReturn("OD-20260922-000001");
        given(passwordEncoder.encode("secret")).willReturn("hashed");
        given(orderRepository.saveAndFlush(any(Order.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> orderCreationService.create(command(5, null), null))
            .isInstanceOf(InsufficientStockException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.CONFLICT);
    }

    // ----- 금액 확인 -----

    @Test
    void create_확인_금액이_현재_금액과_같으면_통과한다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        OrderCreationResult result = orderCreationService.create(command(2, 30_000L), null);

        assertThat(result.order().getTotalAmount()).isEqualTo(30_000L);
    }

    /**
     * 사용자가 15,000 원인 줄 알고 눌렀는데 20,000 원이 조용히 결제되면 안 된다.
     */
    @Test
    void create_확인_금액이_다르면_409이고_재고를_건드리지_않는다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        given(passwordEncoder.encode("secret")).willReturn("hashed");

        assertThatThrownBy(() -> orderCreationService.create(command(2, 25_000L), null))
            .isInstanceOf(OrderAmountMismatchException.class)
            .hasMessageContaining("25000")
            .hasMessageContaining("30000");

        verify(salesStockRepository, never()).reserve(any(), anyInt());
    }

    @Test
    void create_확인_금액을_안_보내면_검증을_건너뛴다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        assertThat(orderCreationService.create(command(2, null), null).created()).isTrue();
    }

    // ----- 멱등 처리 -----

    @Test
    void create_같은_멱등키_재전송은_기존_주문을_돌려주고_새로_만들지_않는다() {
        Order existing = existingOrder();
        given(salesRepository.findById(SALES_ID))
            .willReturn(Optional.of(new Sales(PRODUCT_ID, PRICE, SalesStatus.ON_SALE)));
        given(passwordEncoder.matches("secret", "hashed")).willReturn(true);
        given(orderRepository.findByIdempotencyKey("key-1")).willReturn(Optional.of(existing));

        OrderCreationResult result = orderCreationService.create(command(2, null), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(existing);
        verify(salesStockRepository, never()).reserve(any(), anyInt());
        verify(shoppingClient, never()).findProduct(any());
    }

    /**
     * 두 재전송이 동시에 멱등키 조회를 통과하면 한쪽은 UNIQUE 제약에 걸린다. 그 요청도 실패가
     * 아니라 기존 주문을 받아야 한다.
     */
    @Test
    void create_멱등키_UNIQUE_충돌시_기존_주문으로_연결한다() {
        productExists();
        given(passwordEncoder.encode("secret")).willReturn("hashed");
        Order existing = existingOrder();
        given(salesRepository.findById(SALES_ID))
            .willReturn(Optional.of(new Sales(PRODUCT_ID, PRICE, SalesStatus.ON_SALE)));
        given(passwordEncoder.matches("secret", "hashed")).willReturn(true);
        given(orderRepository.findByIdempotencyKey("key-1"))
            .willReturn(Optional.empty())
            .willReturn(Optional.of(existing));
        given(transactionTemplate.execute(any()))
            .willThrow(new DataIntegrityViolationException("uk_orders_idempotency_key"));

        OrderCreationResult result = orderCreationService.create(command(2, null), "key-1");

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isSameAs(existing);
    }

    /**
     * 멱등키 없이 오는 요청도 허용한다. 이때는 재전송이 별개 주문이 되는데, 이는 클라이언트가
     * 키를 안 보낸 결과이지 서버 오류가 아니다.
     */
    @Test
    void create_멱등키가_없으면_조회하지_않는다() {
        productExists();
        executeTransactionInline();
        salesExists(SalesStatus.ON_SALE);
        reservationSucceeds(3);

        orderCreationService.create(command(1, null), null);

        verify(orderRepository, never()).findByIdempotencyKey(any());
    }

    /**
     * 주문번호는 난수라 같은 날 겹칠 수 있다. 유일성은 DB 제약이 보장하고, 서비스는 새 번호로
     * 다시 시도한다.
     */
    @Test
    void create_주문번호가_겹치면_새_번호로_재시도한다() {
        productExists();
        given(passwordEncoder.encode("secret")).willReturn("hashed");
        given(transactionTemplate.execute(any()))
            .willThrow(new DataIntegrityViolationException("uk_orders_order_number"))
            .willAnswer(invocation -> existingOrder());

        OrderCreationResult result = orderCreationService.create(command(1, null), null);

        assertThat(result.created()).isTrue();
    }

    // ----- 입력 검증 -----

    @Test
    void create_필수값이_없으면_400이고_아무것도_조회하지_않는다() {
        CreateOrderCommand noName = new CreateOrderCommand(
            PRODUCT_ID, 1, " ", "010-1234-5678", "secret", null);

        assertThatThrownBy(() -> orderCreationService.create(noName, null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(shoppingClient, never()).findProduct(any());
    }

    @Test
    void create_수량이_0이하면_400() {
        assertThatThrownBy(() -> orderCreationService.create(command(0, null), null))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orderCreationService.create(command(-1, null), null))
            .isInstanceOf(BusinessException.class);
    }

    private Order existingOrder() {
        return new Order(
            "OD-20260922-000001", SALES_ID, 2, PRICE, "홍길동", "010-1234-5678",
            "hashed", "테스트 상품", "key-1", Instant.now().plus(Duration.ofMinutes(15)));
    }

    /** 스냅샷 필드가 요청 값 그대로 저장되는지 확인용. */
    @Test
    void create_주문자_정보를_그대로_저장한다() {
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
}
