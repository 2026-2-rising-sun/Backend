package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {"commercecore.order.closure-recovery.initial-delay=PT1H"})
@AutoConfigureMockMvc
class OrderDeadlinePostgresTest extends OrderSheetPostgresSupport {
    @Autowired OrderCreationService creation;
    @Autowired OrderService orderQueries;
    @Autowired InMemoryProductLookupStub lookup;
    @Autowired PaymentGroupExpirationScheduler scheduler;
    @MockitoSpyBean OrderTimeSource time;
    Instant created;

    @BeforeEach
    void fixedCreationTime() {
        created = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        doReturn(created).when(time).now();
        jdbc.update("DELETE FROM sku WHERE id=503");
        lookup.register(new ProductSnapshot(101L, "상품101", null, SELLER, true));
        lookup.register(new ProductSnapshot(102L, "상품102", null, SELLER, true));
    }

    com.shoppinglive.commercecore.order.entity.Order direct() {
        return creation.create(
                        new CreateOrderCommand(101L, 2, "회원", "01012345678", MEMBER_A, 8000L),
                        "direct")
                .order();
    }

    long cart(long product, long sku, int quantity) {
        return jdbc.queryForObject(
                "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES(?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                Long.class,
                MEMBER_A,
                product,
                sku,
                quantity);
    }

    @ParameterizedTest(name = "신규 {0} 생성은 10분, 같은 요청 재전송은 기존 기한 보존")
    @ValueSource(strings = {"direct", "cart-single", "cart-group", "sheet-direct", "sheet-cart"})
    void everyNewRouteStoresTenMinutesAndReplayKeepsDeadline(String route) {
        long item = route.contains("cart") ? cart(101, 501, 2) : 0;
        long second = route.equals("cart-group") ? cart(102, 502, 1) : 0;
        Runnable request =
                () -> {
                    switch (route) {
                        case "direct" -> direct();
                        case "cart-single" ->
                                creation.createFromCart(
                                        MEMBER_A,
                                        item,
                                        new CartOrderCommand("회원", "01012345678", 8000L),
                                        "cart-single");
                        case "cart-group" ->
                                legacyGroups.create(
                                        MEMBER_A,
                                        List.of(
                                                new PaymentGroupService.Selection(item, 0L),
                                                new PaymentGroupService.Selection(second, 0L)),
                                        "회원",
                                        "01012345678",
                                        11000L,
                                        "cart-group");
                        case "sheet-direct" ->
                                create("sheet", List.of(new CreateOrderSheetRequest.Item(501L, 2)));
                        case "sheet-cart" ->
                                sheets.create(
                                        MEMBER_A,
                                        "sheet-cart",
                                        new CreateOrderSheetRequest(
                                                null,
                                                List.of(
                                                        new com.shoppinglive.commercecore.cart.dto
                                                                .CartSelection(item, 0L)),
                                                "회원",
                                                "01012345678"));
                        default -> throw new AssertionError(route);
                    }
                };
        request.run();
        var before =
                jdbc.queryForMap(
                        "SELECT id,group_number,expires_at,payable_amount,request_key,policy_version FROM payment_group");
        assertThat(((Timestamp) before.get("expires_at")).toInstant())
                .isEqualTo(created.plusSeconds(600));
        assertThat(((Number) before.get("policy_version")).intValue())
                .isEqualTo(route.startsWith("sheet") ? 1 : 2);
        assertThat(jdbc.queryForList("SELECT expires_at FROM orders", Timestamp.class))
                .allSatisfy(t -> assertThat(t.toInstant()).isEqualTo(created.plusSeconds(600)));
        doReturn(created.plusSeconds(200)).when(time).now();
        request.run();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT id,group_number,expires_at,payable_amount,request_key,policy_version FROM payment_group"))
                .isEqualTo(before);
        assertThat(reserved(501)).isEqualTo(2);
    }

    @Test
    @DisplayName("기존 정책0의15분 기한·번호·금액·참조는 새10분 설정으로 소급 변경하지 않는다")
    void existingFifteenMinuteRowRemainsUntouched() {
        var order = direct();
        jdbc.update(
                "UPDATE payment_group SET policy_version=0,expires_at=?",
                Timestamp.from(created.plusSeconds(900)));
        jdbc.update("UPDATE orders SET expires_at=?", Timestamp.from(created.plusSeconds(900)));
        var groupBefore =
                jdbc.queryForMap(
                        "SELECT group_number,expires_at,payable_amount,request_key FROM payment_group");
        var itemBefore =
                jdbc.queryForMap(
                        "SELECT id,order_number,expires_at,total_amount,sku_id,product_id_snapshot FROM orders");
        doReturn(created.plusSeconds(600)).when(time).now();
        assertThat(legacyGroups.expire(order.getPaymentGroup().getId())).isFalse();
        assertThat(direct().getId()).isEqualTo(order.getId());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT group_number,expires_at,payable_amount,request_key FROM payment_group"))
                .isEqualTo(groupBefore);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT id,order_number,expires_at,total_amount,sku_id,product_id_snapshot FROM orders"))
                .isEqualTo(itemBefore);
        assertThat(reserved(501)).isEqualTo(2);
    }

    @Test
    @DisplayName("신규 구API는 경계 직전에는 유지하고 정확10분 조회에서 해제하며 결제시작을 거절한다")
    void detailReadExpiresExactlyAtDeadlineAndRejectsPayment() {
        var order = direct();
        doReturn(created.plusSeconds(600).minusNanos(1000)).when(time).now();
        assertThat(legacyGroups.expire(order.getPaymentGroup().getId())).isFalse();
        doReturn(created.plusSeconds(600)).when(time).now();
        assertThat(
                        orderQueries
                                .findByOrderNumberAndMemberId(order.getOrderNumber(), MEMBER_A)
                                .getStatus()
                                .name())
                .isEqualTo("FAILED");
        assertThatThrownBy(
                        () ->
                                legacyGroups.start(
                                        MEMBER_A, order.getPaymentGroup().getGroupNumber(), "late"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(reserved(501)).isZero();
        assertThat(available(501)).isEqualTo(20);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_delivery_request", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("정책2 UNKNOWN도 원래10분에 만료하고 늦은 실제Payment 성공은 영속보상만 한 번 만든다")
    void unknownDeadlineAndLateHttpSuccessNeverReviveLegacyOrder() {
        var order = direct();
        var payment = legacyGroups.start(MEMBER_A, order.getPaymentGroup().getGroupNumber(), "pay");
        var unknown =
                new PaymentExecution(
                        payment.requestId(),
                        payment.paymentId(),
                        payment.orderId(),
                        payment.paymentGroupId(),
                        payment.referenceNumber(),
                        payment.amount(),
                        payment.scenario(),
                        PaymentStatus.UNKNOWN,
                        payment.requestedAt(),
                        null,
                        payment.scheduledResolveAt());
        doReturn(created.plusSeconds(599)).when(time).now();
        handler.payment(unknown);
        assertThat(
                        legacyGroups
                                .get(MEMBER_A, order.getPaymentGroup().getGroupNumber())
                                .status()
                                .name())
                .isEqualTo("PAYMENT_CONFIRMING");
        assertThat(reserved(501)).isEqualTo(2);
        doReturn(created.plusSeconds(600)).when(time).now();
        handler.payment(unknown);
        scheduler.run();
        assertThat(
                        legacyGroups
                                .get(MEMBER_A, order.getPaymentGroup().getGroupNumber())
                                .status()
                                .name())
                .isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                .resolvePayment(payment.paymentId());
        dispatcher.payment(payment.paymentId());
        dispatcher.payment(payment.paymentId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_closure", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM refund_request WHERE purpose='COMPENSATION' AND refund_amount=8000",
                                Integer.class))
                .isEqualTo(1);
        assertThat(reserved(501)).isZero();
        assertThat(available(501)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("구API 재요청은 정확 기한에서 만료 상태를 반환하고 기한·식별자를 연장하지 않는다")
    void expiredLegacyReplayReturnsTerminalStateWithSameDeadline() {
        var order = direct();
        doReturn(created.plusSeconds(600)).when(time).now();
        var replay = direct();
        assertThat(replay.getId()).isEqualTo(order.getId());
        assertThat(replay.getOrderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(replay.getExpiresAt()).isEqualTo(created.plusSeconds(600));
        assertThat(replay.getStatus().name()).isEqualTo("FAILED");
        assertThat(available(501)).isEqualTo(20);
    }

    @Test
    @DisplayName("구API 취소 경로에서도 정확 기한의 만료·해제가 거절 예외로 롤백되지 않는다")
    void cancellationAtDeadlineCommitsExpiryInsteadOfRollingItBack() {
        var order = direct();
        doReturn(created.plusSeconds(600)).when(time).now();
        orderQueries.cancelBeforePayment(order.getOrderNumber(), MEMBER_A);
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM payment_group", String.class))
                .isEqualTo("EXPIRED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
    }

    @ParameterizedTest(name = "결제 확정 결과의 생성후 {0}초 경계 판정")
    @ValueSource(ints = {599, 600})
    void confirmedResultChecksStoredDeadlineWithoutPriorExpiry(int elapsed) {
        var order = direct();
        var payment = legacyGroups.start(MEMBER_A, order.getPaymentGroup().getGroupNumber(), "pay");
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                .resolvePayment(payment.paymentId());
        doReturn(created.plusSeconds(elapsed)).when(time).now();
        dispatcher.payment(payment.paymentId());
        assertThat(jdbc.queryForObject("SELECT status FROM payment_group", String.class))
                .isEqualTo(elapsed < 600 ? "PAID" : "FAILED");
        assertThat(available(501)).isEqualTo(elapsed < 600 ? 18 : 20);
        assertThat(reserved(501)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM refund_request WHERE purpose='COMPENSATION'",
                                Integer.class))
                .isEqualTo(elapsed < 600 ? 0 : 1);
    }

    @Test
    @DisplayName("이미 생성된 정책0 확인중 주문은 기존15분 기한과 미확정 호환 처리를 보존한다")
    void existingConfirmingOrderIsNotMigratedToNewExpiryBehavior() {
        var order = direct();
        jdbc.update(
                "UPDATE payment_group SET policy_version=0,expires_at=?",
                Timestamp.from(created.plusSeconds(900)));
        jdbc.update("UPDATE orders SET expires_at=?", Timestamp.from(created.plusSeconds(900)));
        var payment = legacyGroups.start(MEMBER_A, order.getPaymentGroup().getGroupNumber(), "pay");
        doReturn(created.plusSeconds(901)).when(time).now();
        handler.payment(
                new PaymentExecution(
                        payment.requestId(),
                        payment.paymentId(),
                        payment.orderId(),
                        payment.paymentGroupId(),
                        payment.referenceNumber(),
                        payment.amount(),
                        payment.scenario(),
                        PaymentStatus.UNKNOWN,
                        payment.requestedAt(),
                        null,
                        payment.scheduledResolveAt()));
        scheduler.run();
        assertThat(
                        legacyGroups
                                .get(MEMBER_A, order.getPaymentGroup().getGroupNumber())
                                .status()
                                .name())
                .isEqualTo("PAYMENT_CONFIRMING");
        assertThat(
                        jdbc.queryForObject("SELECT expires_at FROM payment_group", Timestamp.class)
                                .toInstant())
                .isEqualTo(created.plusSeconds(900));
        assertThat(reserved(501)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_payment_closure", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("스케줄러는 결제확인중 신규정책2를 정확기한에 찾아 만료하며 정책0은 보존한다")
    void schedulerIncludesNewConfirmingOrdersAtExactBoundary() {
        var order = direct();
        legacyGroups.start(MEMBER_A, order.getPaymentGroup().getGroupNumber(), "pay");
        doReturn(created.plusSeconds(600)).when(time).now();
        scheduler.run();
        scheduler.run();
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM payment_group", String.class))
                .isEqualTo("EXPIRED");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
    }
}
